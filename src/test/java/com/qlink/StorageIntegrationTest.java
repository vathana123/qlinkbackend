package com.qlink;

import com.qlink.features.storage.entity.StoredFile;
import com.qlink.features.storage.repository.StoredFileRepository;
import com.qlink.features.storage.provider.LocalFileStorageProvider;
import com.qlink.features.storage.service.*;
import com.qlink.features.user.entity.User;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.auth.entity.AuthSession;
import com.qlink.features.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @Testcontainers @ActiveProfiles("test")
class StorageIntegrationTest {
    @Container static final MySQLContainer mysql=new MySQLContainer("mysql:8.4").withDatabaseName("storage_test");
    @TempDir static Path directory;
    static final Path keys=keys();
    static Path keys() {
        try {
            Path dir=Files.createTempDirectory("qlink-storage-keys-");
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
            pem(dir.resolve("private.pem"),"PRIVATE KEY",pair.getPrivate().getEncoded());
            pem(dir.resolve("public.pem"),"PUBLIC KEY",pair.getPublic().getEncoded()); return dir;
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    static void pem(Path p,String label,byte[] bytes) throws Exception {
        Files.writeString(p,"-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+label+"-----\n");
    }
    @AfterAll static void removeKeys() throws Exception { Files.delete(keys.resolve("private.pem")); Files.delete(keys.resolve("public.pem")); Files.delete(keys); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",mysql::getJdbcUrl); r.add("spring.datasource.username",mysql::getUsername); r.add("spring.datasource.password",mysql::getPassword);
        r.add("qlink.jwt.private-key",()->keys.resolve("private.pem").toUri().toString()); r.add("qlink.jwt.public-key",()->keys.resolve("public.pem").toUri().toString());
        r.add("app.storage.root-path",()->directory.toString()); r.add("app.storage.max-file-size",()->"1KB");
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users; @Autowired AuthSessionRepository sessions; @Autowired JwtEncoder encoder;
    @Autowired StorageCleanupService cleanup;
    @MockitoSpyBean StoredFileRepository files;
    @MockitoSpyBean LocalFileStorageProvider provider;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6V7sAAAAASUVORK5CYII=");
    record Account(UUID id,UUID session,String token) {}
    Account account() {
        User user=new User(); user.setDisplayName("Storage test"); user=users.saveAndFlush(user);
        AuthSession session=new AuthSession(); session.setUserId(user.getId()); session.setExpiresAt(Instant.now().plusSeconds(3600)); session=sessions.save(session);
        var claims=JwtClaimsSet.builder().issuer("qlink-api").subject(user.getId().toString()).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900)).claim("token_use","access").claim("sid",session.getId().toString()).build();
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
        return new Account(user.getId(),session.getId(),token);
    }
    MockMultipartFile png() { return new MockMultipartFile("file","../../photo.png","application/x-msdownload",PNG); }
    ResultActions upload(Account a,MockMultipartFile f) throws Exception { return mvc.perform(multipart("/api/v1/storage/files").file(f).header("Authorization","Bearer "+a.token())); }
    UUID upload(Account a) throws Exception {
        String body=upload(a,png()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).at("/data/id").asText());
    }
    ResultActions request(Account a,String method,UUID id,String suffix) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),"/api/v1/storage/files/"+id+suffix).header("Authorization","Bearer "+a.token()));
    }
    long diskCount() throws Exception { try(var paths=Files.list(directory)) { return paths.count(); } }
    @Test void uploadPersistsSafeMetadataHashAndAuditingAndStreamsDownload() throws Exception {
        Account a=account(); UUID id=upload(a);
        StoredFile file=files.findById(id).orElseThrow();
        assertThat(file.getStorageKey()).matches("[0-9a-f-]{36}[.]blob");
        assertThat(file.getStorageKey()).doesNotContain("photo");
        assertThat(file.getUploadedBy()).isEqualTo(a.id()); assertThat(file.getCreatedBy()).isEqualTo(a.id());
        assertThat(file.getChecksumSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG)));
        request(a,"GET",id,"").andExpect(status().isOk()).andExpect(jsonPath("$.data.originalName").value("photo.png"))
            .andExpect(jsonPath("$.data.contentType").value("image/png")).andExpect(jsonPath("$.data.sizeBytes").value(PNG.length))
            .andExpect(jsonPath("$.data.storageKey").doesNotExist()).andExpect(jsonPath("$.data.uploadedBy").doesNotExist());
        request(a,"GET",id,"/download").andExpect(status().isOk()).andExpect(content().bytes(PNG))
            .andExpect(header().string("Content-Type","image/png")).andExpect(header().string("Content-Length",""+PNG.length))
            .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;")));
    }
    @Test void onlyOwnerMayReadOrDeleteAndAnonymousCannotUpload() throws Exception {
        Account a=account(),b=account(); UUID id=upload(a);
        request(b,"GET",id,"").andExpect(status().isNotFound()); request(b,"GET",id,"/download").andExpect(status().isNotFound());
        request(b,"DELETE",id,"").andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/storage/files").file(png())).andExpect(status().isUnauthorized());
        request(a,"GET",id,"/download").andExpect(status().isOk());
    }
    @Test void emptyOversizedUnsupportedAndSpoofedContentLeaveNoMetadataOrBytes() throws Exception {
        Account a=account(); long count=files.count(),disk=diskCount();
        upload(a,new MockMultipartFile("file","empty.txt","text/plain",new byte[0])).andExpect(status().isBadRequest());
        upload(a,new MockMultipartFile("file","big.txt","text/plain",new byte[1025])).andExpect(status().is(413));
        upload(a,new MockMultipartFile("file","virus.png","image/png",new byte[]{'M','Z',0,0,1,2})).andExpect(status().is(415));
        upload(a,new MockMultipartFile("file","page.html","text/html","<html>unsafe</html>".getBytes())).andExpect(status().is(415));
        mvc.perform(multipart("/api/v1/storage/files").file(png()).param("category","AUDIO").header("Authorization","Bearer "+a.token())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/storage/files").header("Authorization","Bearer "+a.token())).andExpect(status().isBadRequest());
        assertThat(files.count()).isEqualTo(count); assertThat(diskCount()).isEqualTo(disk);
    }
    @Test void deletionIsSoftIdempotentAndPhysicalFailureCanBeRetried() throws Exception {
        Account a=account(); UUID id=upload(a); String key=files.findById(id).orElseThrow().getStorageKey();
        doThrow(new IOException("private filesystem error")).when(provider).delete(key);
        request(a,"DELETE",id,"").andExpect(status().isOk());
        assertThat(files.findById(id)).isEmpty(); assertThat(provider.exists(key)).isTrue();
        request(a,"GET",id,"/download").andExpect(status().isNotFound()); request(a,"GET",id,"").andExpect(status().isNotFound());
        var tombstone=files.findTombstone(id).orElseThrow();
        assertThat(tombstone.getDeletedBy()).isEqualTo(a.id()); assertThat(tombstone.getStatus().name()).isEqualTo("DELETED");
        doCallRealMethod().when(provider).delete(key);
        request(a,"DELETE",id,"").andExpect(status().isOk()); request(a,"DELETE",id,"").andExpect(status().isOk());
        assertThat(provider.exists(key)).isFalse();
    }
    @Test void missingBytesAreSafe404AndUnavailableMetadataCannotDownload() throws Exception {
        Account a=account(); UUID id=upload(a); var file=files.findById(id).orElseThrow();
        provider.delete(file.getStorageKey()); request(a,"GET",id,"/download").andExpect(status().isNotFound());
        UUID pending=upload(a);
        jdbc.update("update stored_files set status='PENDING' where id=UUID_TO_BIN(?)",pending.toString());
        request(a,"GET",pending,"/download").andExpect(status().isNotFound());
        assertThat(cleanup.cleanupDeleted(pending)).isFalse();
    }
    @Test void writeAndDatabaseFailuresCleanUpAndDoNotDisclosePaths() throws Exception {
        Account a=account(); long count=files.count(),disk=diskCount();
        doThrow(new IOException("C:/private/root")).when(provider).store(any(),anyString(),anyLong(),any());
        upload(a,png()).andExpect(status().is(503)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C:/private"))));
        reset(provider);
        doThrow(new DataAccessResourceFailureException("simulated database failure")).when(files).saveAndFlush(any(StoredFile.class));
        upload(a,png()).andExpect(status().is(503));
        assertThat(files.count()).isEqualTo(count); assertThat(diskCount()).isEqualTo(disk);
    }
    @Test void deletionDatabaseFailureKeepsBytesAndAccessIntact() throws Exception {
        Account a=account(); UUID id=upload(a); String key=files.findById(id).orElseThrow().getStorageKey();
        doThrow(new DataAccessResourceFailureException("simulated database failure")).when(files).flush();
        request(a,"DELETE",id,"").andExpect(status().isInternalServerError());
        assertThat(files.findById(id)).isPresent(); assertThat(provider.exists(key)).isTrue();
        request(a,"GET",id,"/download").andExpect(status().isOk());
    }
    @Test void revokedSessionAndInvalidUuidAreRejected() throws Exception {
        Account a=account(); UUID id=upload(a);
        mvc.perform(get("/api/v1/storage/files/not-a-uuid").header("Authorization","Bearer "+a.token())).andExpect(status().isBadRequest());
        jdbc.update("update auth_sessions set revoked_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",a.session().toString());
        request(a,"GET",id,"").andExpect(status().isUnauthorized()); upload(a,png()).andExpect(status().isUnauthorized());
    }
    @Test void actualHttpMultipartLimitAndExactSizeAreConsistent() throws Exception {
        Account a=account();
        for(int size:new int[]{1024,1025}) {
            String boundary="qlink-boundary";
            String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\nContent-Type: text/plain\r\n\r\n"+"a".repeat(size)+"\r\n--"+boundary+"--\r\n";
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/storage/files"))
                .header("Authorization","Bearer "+a.token()).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(size==1024?201:413);
        }
    }
    @Test void swaggerIncludesStorageAndExistingEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/storage/files'].post.requestBody.content['multipart/form-data']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/storage/files/{fileId}/download'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/auth/signin']").exists());
    }

    ResultActions sendAttachment(Account sender, Account recipient, UUID file, UUID key, String type) throws Exception {
        return mvc.perform(post("/api/v1/chat/personal/messages").header("Authorization","Bearer "+sender.token())
            .contentType("application/json").content(json.writeValueAsString(Map.of("recipientId",recipient.id(),
                "clientMessageId",key,"type",type,"fileId",file,"content",""))));
    }
    @Test void attachmentsPersistWithMetadataAndAreAuthorizedAndIdempotent() throws Exception {
        Account a=account(),b=account(),outsider=account(); UUID file=upload(a), key=UUID.randomUUID();
        sendAttachment(b,a,file,UUID.randomUUID(),"IMAGE").andExpect(status().isNotFound());
        sendAttachment(a,b,file,UUID.randomUUID(),"FILE").andExpect(status().isBadRequest());
        var sent=sendAttachment(a,b,file,key,"IMAGE").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message.attachment.originalName").value("photo.png"))
            .andExpect(jsonPath("$.data.message.attachment.contentType").value("image/png"))
            .andExpect(jsonPath("$.data.message.attachment.sizeBytes").value(PNG.length))
            .andReturn().getResponse().getContentAsString();
        var data=json.readTree(sent).get("data");
        String id=data.at("/message/id").asText(),room=data.at("/room/id").asText();
        sendAttachment(a,b,file,key,"IMAGE").andExpect(status().isOk()).andExpect(jsonPath("$.data.message.id").value(id));
        sendAttachment(a,b,file,key,"FILE").andExpect(status().isConflict());
        sendAttachment(a,b,file,UUID.randomUUID(),"IMAGE").andExpect(status().isBadRequest());
        request(a,"GET",file,"/download").andExpect(status().isOk()).andExpect(content().bytes(PNG));
        request(b,"GET",file,"/download").andExpect(status().isOk()).andExpect(content().bytes(PNG));
        request(outsider,"GET",file,"/download").andExpect(status().isNotFound());
        request(a,"DELETE",file,"").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/chat/rooms/"+room+"/messages").header("Authorization","Bearer "+b.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id))
            .andExpect(jsonPath("$.data.items[0].attachment.originalName").value("photo.png"));
        jdbc.update("update chat_room_members set deleted_at=UTC_TIMESTAMP(6) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id().toString());
        request(b,"GET",file,"/download").andExpect(status().isNotFound());
    }
    @Test void documentMessagesAndConfiguredPolicyUseExistingStorage() throws Exception {
        Account a=account(),b=account();
        mvc.perform(get("/api/v1/storage/files/policy").header("Authorization","Bearer "+a.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.maxSizeBytes").value(1024))
            .andExpect(jsonPath("$.data.extensions.docx").exists());
        String uploaded=upload(a,new MockMultipartFile("file","notes.txt","text/plain","Hello document".getBytes()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID file=UUID.fromString(json.readTree(uploaded).at("/data/id").asText());
        sendAttachment(a,b,file,UUID.randomUUID(),"FILE").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message.type").value("FILE"))
            .andExpect(jsonPath("$.data.message.attachment.originalName").value("notes.txt"));
        request(b,"GET",file,"/download").andExpect(status().isOk()).andExpect(content().string("Hello document"));
    }
}
