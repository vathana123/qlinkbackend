package com.qlink;

import com.qlink.features.auth.enums.AuthProvider;
import com.qlink.features.user.service.UserService;
import com.qlink.security.oauth.*;
import com.qlink.security.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ChatIntegrationTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withDatabaseName("qlink_test");
    static final Path keyDir = generateKeys();
    @org.junit.jupiter.api.io.TempDir static Path storageDir;
    static Path generateKeys() {
        try {
            Path dir = Files.createTempDirectory("qlink-test-keys-");
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair = generator.generateKeyPair();
            writePem(dir.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
            writePem(dir.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
            return dir;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    static void writePem(Path path, String label, byte[] bytes) throws Exception {
        Files.writeString(path, "-----BEGIN " + label + "-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes) + "\n-----END " + label + "-----\n");
    }
    @AfterAll static void removeKeys() throws Exception {
        Files.deleteIfExists(keyDir.resolve("private.pem")); Files.deleteIfExists(keyDir.resolve("public.pem")); Files.deleteIfExists(keyDir);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("qlink.push.initial-delay-ms", () -> "3600000");
        registry.add("app.storage.root-path", () -> storageDir.toString());
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("qlink.jwt.private-key", () -> keyDir.resolve("private.pem").toUri().toString());
        registry.add("qlink.jwt.public-key", () -> keyDir.resolve("public.pem").toUri().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired UserService users;
    @Autowired JwtEncoder encoder;
    @Autowired com.qlink.features.storage.service.VoiceCleanup voiceCleanup;
    @MockitoBean ProviderTokenVerifier verifier;
    @MockitoBean com.qlink.features.push.PushGateway pushGateway;
    @Autowired com.qlink.features.push.PushService push;

    String body(Object value) { return json.writeValueAsString(value); }
    JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data"); }
    MvcResult postJson(String path, Object payload, int status) throws Exception {
        return mvc.perform(post("/api/v1/auth/" + path).contentType("application/json").content(body(payload)))
                .andExpect(status().is(status)).andReturn();
    }
    Map<String, String> provider(String token) { return Map.of("provider", "GOOGLE", "idToken", token); }
    JsonNode signup(String subject) throws Exception {
        when(verifier.verify(AuthProvider.GOOGLE, subject)).thenReturn(new ProviderIdentity(subject, "shared@example.com", "User", null, null));
        return data(postJson("signup", provider(subject), 201));
    }

    record Account(String id, String token) {}
    Account account() throws Exception {
        var result=signup(UUID.randomUUID().toString());
        return new Account(result.get("user").get("id").asText(),result.get("accessToken").asText());
    }
    MvcResult request(Account user,String method,String path,Object payload,int expected) throws Exception {
        var builder=switch(method) {
            case "POST" -> post(path); case "PATCH" -> patch(path); case "PUT" -> put(path);
            case "DELETE" -> delete(path); default -> get(path);
        };
        builder.header("Authorization","Bearer "+user.token());
        if(payload!=null)builder.contentType("application/json").content(body(payload));
        return mvc.perform(builder).andExpect(status().is(expected)).andReturn();
    }
    Map<String,Object> first(Account recipient,String key,String text) {
        return Map.of("recipientId",recipient.id(),"clientMessageId",key,"content",text);
    }
    String key() { return UUID.randomUUID().toString(); }
    JsonNode send(Account a,Account b,String key,String content) throws Exception {
        return data(request(a,"POST","/api/v1/chat/personal/messages",first(b,key,content),200));
    }
    @Test void searchPersonalRoomsMessagesAndPrivateFields() throws Exception {
        var a=account(); var b=account(); var outsider=account();
        jdbc.update("update users set display_name='Search Friend',username='search_friend' where id=UUID_TO_BIN(?)", b.id());
        var sent=send(a,b,key(),"Search needle hello");
        String room=sent.get("room").get("id").asText();
        var conversations=data(request(a,"GET","/api/v1/chat/search/rooms?q=FRIEND",null,200));
        assertThat(conversations.get("items").size()).isEqualTo(1);
        assertThat(conversations.get("items").get(0).get("participant").has("email")).isFalse();
        var matches=data(request(a,"GET","/api/v1/chat/search/messages?q=needle",null,200));
        assertThat(matches.get("items").get(0).get("message").get("id")).isEqualTo(sent.get("message").get("id"));
        assertThat(matches.get("items").get(0).get("room").get("id").asText()).isEqualTo(room);
        assertThat(data(request(outsider,"GET","/api/v1/chat/search/messages?q=needle",null,200)).get("items").isEmpty()).isTrue();
        assertThat(data(request(outsider,"GET","/api/v1/chat/search/rooms?q=friend",null,200)).get("items").isEmpty()).isTrue();
        request(outsider,"GET","/api/v1/chat/rooms/"+room+"/search?q=needle",null,403);
        request(a,"GET","/api/v1/chat/search/messages?q=x",null,400);
        request(a,"GET","/api/v1/chat/search/messages?q=needle&size=51",null,400);
        request(a,"GET","/api/v1/chat/search/messages?q=needle&cursor=invalid",null,400);
        request(a,"GET","/api/v1/chat/search/messages?q=needle&from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z",null,400);
    }

    @Test void searchGroupMembershipHistoryAndDeletionAreEnforced() throws Exception {
        var a=account(); var b=account(); var outsider=account();
        for (String policy:List.of("VIEW_ALL_MESSAGES","VIEW_AFTER_JOIN")) {
            String room=managedGroup(a,b,policy);
            var sent=data(request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","history needle"),200));
            String id=sent.get("message").get("id").asText();
            String path="/api/v1/chat/rooms/"+room+"/search?q=needle";
            String context="/api/v1/chat/rooms/"+room+"/messages/"+id+"/context";
            jdbc.update("update chat_messages set created_at='2026-01-01' where id=UUID_TO_BIN(?)",id);
            jdbc.update("update chat_room_members set joined_at='2026-01-01' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,a.id());
            jdbc.update("update chat_room_members set joined_at='2026-01-02' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
            int expected=policy.equals("VIEW_ALL_MESSAGES")?1:0;
            assertThat(data(request(b,"GET",path,null,200)).get("items").size()).isEqualTo(expected);
            assertThat(data(request(b,"GET","/api/v1/chat/search/messages?q=needle",null,200)).get("items").size()).isEqualTo(expected);
            request(b,"GET",context,null,expected==1?200:404);
            request(outsider,"GET",path,null,403);
            assertThat(data(request(b,"GET","/api/v1/chat/search/rooms?q=managed",null,200)).get("items").size()).isEqualTo(1);
            // At the membership boundary the message is visible.
            jdbc.update("update chat_room_members set joined_at='2026-01-01' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
            assertThat(data(request(b,"GET",path,null,200)).get("items").size()).isEqualTo(1);
            request(b,"DELETE","/api/v1/chat/rooms/"+room+"/messages/"+id,null,200);
            assertThat(data(request(b,"GET",path,null,200)).get("items").isEmpty()).isTrue();
            assertThat(data(request(b,"GET","/api/v1/chat/search/messages?q=needle",null,200)).get("items").isEmpty()).isTrue();
            request(b,"GET",context,null,404);
            assertThat(data(request(a,"GET",path,null,200)).get("items").size()).isEqualTo(1);
            request(a,"DELETE","/api/v1/chat/rooms/"+room+"/messages/"+id+"?everyone=true",null,200);
            assertThat(data(request(a,"GET",path,null,200)).get("items").isEmpty()).isTrue();
            request(a,"GET",context,null,404);
            jdbc.update("update chat_room_members set deleted_at=UTC_TIMESTAMP(6) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
            request(b,"GET",path,null,403);
            assertThat(data(request(b,"GET","/api/v1/chat/search/rooms?q=managed",null,200)).get("items").isEmpty()).isTrue();
        }
    }

    @Test void searchConversationPagesIncludeOnlyAccessiblePersonalAndGroupRooms() throws Exception {
        var a=account(); var b=account(); var c=account(); var outsider=account();
        jdbc.update("update users set display_name='Needle Bob' where id=UUID_TO_BIN(?)",b.id());
        jdbc.update("update users set display_name='Needle Carol' where id=UUID_TO_BIN(?)",c.id());
        send(a,b,key(),"contact"); send(a,c,key(),"contact");
        for(String type:List.of("GROUP_PRIVATE","GROUP_PUBLIC"))
            request(a,"POST","/api/v1/chat/groups",Map.of("name","Needle group","type",type,"memberIds",List.of(b.id())),200);
        var ids=new ArrayList<String>(); String cursor=null;
        do {
            var page=data(request(a,"GET","/api/v1/chat/search/rooms?q=needle&size=1"+(cursor==null?"":"&cursor="+cursor),null,200));
            for(var item:page.get("items")) ids.add(item.get("id").asText());
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
        } while(cursor!=null && ids.size()<10);
        assertThat(ids).hasSize(4).doesNotHaveDuplicates();
        assertThat(data(request(outsider,"GET","/api/v1/chat/search/rooms?q=needle",null,200)).get("items").isEmpty()).isTrue();
        jdbc.update("update chat_rooms set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",ids.get(0));
        assertThat(data(request(a,"GET","/api/v1/chat/search/rooms?q=needle",null,200)).get("items").size()).isEqualTo(3);
        // Verify V16 on the disposable MySQL schema, including column order.
        assertThat(jdbc.queryForObject("select database()",String.class)).isEqualTo("qlink_test");
        assertThat(jdbc.queryForObject("select version()",String.class)).startsWith("8.4.");
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='16' and success=true",Integer.class)).isEqualTo(1);
        var indexes=Map.of(
            "idx_message_search_room","room_id,type,deleted_at,removed_at,created_at,id",
            "idx_message_search_global","type,deleted_at,removed_at,created_at,id",
            "idx_message_search_sender","sender_id,type,deleted_at,removed_at,created_at,id",
            "idx_room_search_cursor","deleted_at,created_at,id");
        for(var index:indexes.entrySet()) {
            String table=index.getKey().equals("idx_room_search_cursor")?"chat_rooms":"chat_messages";
            assertThat(jdbc.queryForObject("select group_concat(column_name order by seq_in_index separator ',') from information_schema.statistics where table_schema=database() and table_name=? and index_name=?",String.class,table,index.getKey()))
                .as(index.getKey()).isEqualTo(index.getValue());
        }
    }

    @Test void searchStablePaginationLiteralMatchingAndFilters() throws Exception {
        var a=account(); var b=account(); String room=null;
        for (int i=0;i<5;i++) room=send(a,b,key(),"literal 50%_! needle "+i).get("room").get("id").asText();
        jdbc.update("update chat_messages set created_at='2026-01-01 00:00:00.123456' where room_id=UUID_TO_BIN(?)",room);
        String path="/api/v1/chat/rooms/"+room+"/search?q=needle&size=2";
        var ids=new ArrayList<String>(); String cursor=null;
        do {
            var page=data(request(a,"GET",path+(cursor==null?"":"&cursor="+cursor),null,200));
            for(var match:page.get("items")) ids.add(match.get("message").get("id").asText());
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
            if(ids.size()==2) send(a,b,key(),"new needle arrives between pages");
        } while(cursor!=null && ids.size()<10);
        assertThat(ids).hasSize(5).doesNotHaveDuplicates();
        assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(data(request(a,"GET",path+"&senderId="+b.id(),null,200)).get("items").isEmpty()).isTrue();
        assertThat(data(request(a,"GET",path+"&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z",null,200)).get("items").size()).isEqualTo(2);
        var result=mvc.perform(get("/api/v1/chat/search/messages").param("q","50%_!")
            .header("Authorization","Bearer "+a.token())).andExpect(status().isOk()).andReturn();
        assertThat(data(result).get("items").size()).isEqualTo(5);
        jdbc.update("update chat_messages set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",ids.get(0));
        result=mvc.perform(get("/api/v1/chat/search/messages").param("q","50%_!")
            .header("Authorization","Bearer "+a.token())).andExpect(status().isOk()).andReturn();
        assertThat(data(result).get("items").size()).isEqualTo(4);
        assertThat(data(request(a,"GET","/api/v1/chat/search/messages?q=absent",null,200)).get("nextCursor").isNull()).isTrue();
    }

    @Test void searchContextLoadsBoundedOlderAndNewerWindows() throws Exception {
        var a=account(); var b=account(); var outsider=account();
        var ids=new ArrayList<String>(); String room=null;
        for(int i=0;i<9;i++) {
            var sent=send(a,b,key(),"context needle "+i); room=sent.get("room").get("id").asText();
            String id=sent.get("message").get("id").asText(); ids.add(id);
            jdbc.update("update chat_messages set created_at=? where id=UUID_TO_BIN(?)","2026-01-01 00:00:0"+i,id);
        }
        String path="/api/v1/chat/rooms/"+room+"/messages";
        var window=data(request(a,"GET",path+"/"+ids.get(4)+"/context?size=2",null,200));
        assertThat(window.get("items").size()).isEqualTo(5);
        assertThat(window.get("items").get(2).get("id").asText()).isEqualTo(ids.get(4));
        var older=data(request(a,"GET",path+"?cursor="+window.get("nextCursor").asText(),null,200));
        assertThat(older.get("items").size()).isEqualTo(2);
        var newer=data(request(a,"GET",path+"/newer?cursor="+window.get("newerCursor").asText(),null,200));
        assertThat(newer.get("items").size()).isEqualTo(2);
        assertThat(newer.get("items").get(0).get("id").asText()).isEqualTo(ids.get(8));
        assertThat(newer.get("nextCursor").isNull()).isTrue();
        request(outsider,"GET",path+"/"+ids.get(4)+"/context",null,403);
        String otherRoom=send(a,outsider,key(),"other conversation").get("room").get("id").asText();
        request(a,"GET","/api/v1/chat/rooms/"+otherRoom+"/messages/"+ids.get(4)+"/context",null,404);
    }

    @Test void usernamesNormalizeRemainUniqueAndKeepPublicProfilesPrivate() throws Exception {
        var a=account();var b=account();String username="member_"+key().substring(0,8);
        var saved=data(request(a,"PATCH","/api/v1/users/me/username",Map.of("username",username.toUpperCase()),200));
        assertThat(saved.get("username").asText()).isEqualTo(username);
        request(b,"PATCH","/api/v1/users/me/username",Map.of("username",username),409);
        for(String invalid:List.of("Admin","SYSTEM","support","ab","has space","with-dash"))
            request(a,"PATCH","/api/v1/users/me/username",Map.of("username",invalid),400);
        var search=data(request(b,"GET","/api/v1/users/search?q="+username,null,200));
        assertThat(search.get("content").size()).isEqualTo(1);
        assertThat(search.get("content").get(0).has("email")).isFalse();
        assertThat(data(request(a,"GET","/api/v1/users/search?q="+username,null,200)).get("content").size()).isZero();
        assertThatThrownBy(()->jdbc.update("update users set username=? where id=UUID_TO_BIN(?)",username.toUpperCase(),b.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        users.softDelete(UUID.fromString(a.id()),UUID.fromString(a.id()));
        assertThat(data(request(b,"GET","/api/v1/users/search?q="+username,null,200)).get("content").size()).isZero();
        request(b,"GET","/api/v1/users/"+a.id()+"/public",null,404);
    }
    @Test void qrResolutionAndDraftLookupDoNotCreateRooms() throws Exception {
        var a=account();var b=account();
        var user=data(request(a,"GET","/api/v1/users/"+b.id()+"/public",null,200));
        assertThat(user.get("id").asText()).isEqualTo(b.id());assertThat(user.has("email")).isFalse();
        request(a,"GET","/api/v1/users/"+a.id()+"/public",null,400);
        request(a,"GET","/api/v1/users/"+key()+"/public",null,404);
        request(a,"GET","/api/v1/users/not-a-uuid/public",null,400);
        for(int i=0;i<2;i++) {
            var lookup=data(request(a,"GET","/api/v1/chat/personal/with/"+b.id(),null,200));
            assertThat(lookup.get("exists").asBoolean()).isFalse();assertThat(lookup.get("room").isNull()).isTrue();
        }
        assertThat(data(request(a,"GET","/api/v1/chat/rooms",null,200)).get("content").size()).isZero();
    }
    @Test void firstMessageCreatesExactlyTwoMembersAndRetriesAreIdempotent() throws Exception {
        var a=account();var b=account();var outsider=account();String key=key();
        var sent=send(a,b,key,"Hello");String room=sent.get("room").get("id").asText();
        assertThat(sent.get("room").get("creatorId").asText()).isEqualTo(a.id());
        assertThat(jdbc.queryForObject("select count(*) from chat_room_members where room_id=UUID_TO_BIN(?) and deleted_at is null",Integer.class,room)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where room_id=UUID_TO_BIN(?) and sender_id=created_by",Integer.class,room)).isEqualTo(1);
        var retry=send(a,b,key,"Hello");assertThat(retry.get("message").get("id")).isEqualTo(sent.get("message").get("id"));
        var existing=data(request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key,"content","Hello"),200));
        assertThat(existing.get("message").get("id")).isEqualTo(sent.get("message").get("id"));
        request(a,"POST","/api/v1/chat/personal/messages",first(b,key,"Changed"),409);
        request(a,"POST","/api/v1/chat/personal/messages",first(outsider,key,"Hello"),409);
        request(outsider,"GET","/api/v1/chat/rooms/"+room+"/messages",null,403);
        request(outsider,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","Intrude"),403);
        assertThat(data(request(b,"GET","/api/v1/chat/personal/with/"+a.id(),null,200)).get("room").get("id").asText()).isEqualTo(room);
        request(a,"POST","/api/v1/chat/personal/messages",first(b,key(),"   "),400);
        request(a,"POST","/api/v1/chat/personal/messages",first(b,key(),"x".repeat(4001)),400);
        request(a,"POST","/api/v1/chat/personal/messages",first(a,key(),"Self"),400);
    }
    @Test void concurrentOppositeFirstMessagesReuseWinningRoomAndCreator() throws Exception {
        var a=account();var b=account();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return send(a,b,key(),"from A");});
            var two=pool.submit(()->{start.await();return send(b,a,key(),"from B");});start.countDown();
            var first=one.get(30,TimeUnit.SECONDS);var second=two.get(30,TimeUnit.SECONDS);
            String room=first.get("room").get("id").asText();
            assertThat(second.get("room").get("id").asText()).isEqualTo(room);
            String creator=first.get("room").get("creatorId").asText();
            assertThat(second.get("room").get("creatorId").asText()).isEqualTo(creator);
            assertThat(jdbc.queryForObject("select count(*) from chat_room_members where room_id=UUID_TO_BIN(?)",Integer.class,room)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from chat_messages where room_id=UUID_TO_BIN(?)",Integer.class,room)).isEqualTo(2);
            String earliest=jdbc.queryForObject("select BIN_TO_UUID(sender_id) from chat_messages where room_id=UUID_TO_BIN(?) order by created_at,id limit 1",String.class,room);
            assertThat(creator).isEqualTo(earliest);
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentRetriesPersistOneMessage() throws Exception {
        var a=account();var b=account();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);String key=key();
        try {
            Callable<JsonNode> action=()->{start.await();return send(a,b,key,"same");};
            var one=pool.submit(action);var two=pool.submit(action);start.countDown();
            var first=one.get(30,TimeUnit.SECONDS);var second=two.get(30,TimeUnit.SECONDS);
            assertThat(first.get("message").get("id")).isEqualTo(second.get("message").get("id"));
            assertThat(jdbc.queryForObject("select count(*) from chat_messages where sender_id=UUID_TO_BIN(?) and client_message_id=UUID_TO_BIN(?)",Integer.class,a.id(),key)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void cursorOrderingTiesSoftDeletionAndHistoricalRoomPairs() throws Exception {
        var a=account();var b=account();String key=key();
        var first=send(a,b,key,"first");String room=first.get("room").get("id").asText();
        for(int i=0;i<4;i++)send(a,b,key(),"message "+i);
        jdbc.update("update chat_messages set created_at='2026-01-01 00:00:00.123456' where room_id=UUID_TO_BIN(?)",room);
        String path="/api/v1/chat/rooms/"+room+"/messages?size=2";
        Set<String> ids=new HashSet<>();String cursor=null;
        do {
            var result=data(request(a,"GET",path+(cursor==null?"":"&cursor="+cursor),null,200));
            for(var message:result.get("items"))assertThat(ids.add(message.get("id").asText())).isTrue();
            cursor=result.get("nextCursor").isNull()?null:result.get("nextCursor").asText();
        }while(cursor!=null);
        assertThat(ids).hasSize(5);
        request(a,"GET",path+"&cursor=broken",null,400);
        jdbc.update("update chat_messages set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",first.get("message").get("id").asText());
        assertThat(data(request(a,"GET","/api/v1/chat/rooms/"+room+"/messages",null,200)).get("items").size()).isEqualTo(4);
        request(a,"POST","/api/v1/chat/personal/messages",first(b,key,"first"),409);
        // Active uniqueness rejects duplicates; a tombstoned pair permits a new room.
        assertThatThrownBy(()->jdbc.update("insert into chat_rooms (id,type,creator_id,personal_pair_key,created_at,updated_at) select UUID_TO_BIN(UUID()),type,creator_id,personal_pair_key,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) from chat_rooms where id=UUID_TO_BIN(?)",room))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update chat_rooms set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",room);
        request(a,"GET","/api/v1/chat/rooms/"+room+"/messages",null,404);
        assertThat(data(request(a,"GET","/api/v1/chat/rooms",null,200)).get("content").size()).isZero();
        String replacement=send(b,a,key(),"new room").get("room").get("id").asText();
        assertThat(replacement).isNotEqualTo(room);
    }

    MvcResult search(Account viewer, String q, int page, int size, int expected) throws Exception {
        return mvc.perform(get("/api/v1/users/search").param("q",q).param("page",String.valueOf(page)).param("size",String.valueOf(size))
            .header("Authorization","Bearer "+viewer.token())).andExpect(status().is(expected)).andReturn();
    }
    @Test void discoveryMatchesAllFieldsWithoutDisclosingContacts() throws Exception {
        var viewer=account();var target=account();String unique=key().replace("-", "").substring(0,12);
        String name="Alice "+unique, username="member_"+unique, email=unique+"@example.com";
        jdbc.update("update users set display_name=?,username=?,email=? where id=UUID_TO_BIN(?)",name,username,"  "+email.toUpperCase()+"  ",target.id());
        request(target,"PATCH","/api/v1/users/me/phone-number",Map.of("phoneNumber","00 855 (12) 345-678"),200);
        assertThat(jdbc.queryForObject("select phone_number from users where id=UUID_TO_BIN(?)",String.class,target.id())).isEqualTo("+85512345678");
        for(String term:List.of("ALICE "+unique, "MEMBER_"+unique, "  "+email.toUpperCase()+"  ", "+855 (12) 345-678", "0085512345678")) {
            var result=search(viewer,term,0,20,200);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            var content=data(result).get("content");assertThat(content.size()).isEqualTo(1);
            assertThat(content.get(0).get("id").asText()).isEqualTo(target.id());
            assertThat(content.get(0).has("email")).isFalse();assertThat(content.get(0).has("phoneNumber")).isFalse();
            assertThat(content.get(0).size()).isEqualTo(4);
        }
        for(String partial:List.of(email.substring(0,email.length()-4), "+85512345", "12345678", "%__"))
            assertThat(data(search(viewer,partial,0,20,200)).get("content").size()).isZero();
        request(target,"PATCH","/api/v1/users/me/phone-number",Map.of("phoneNumber","012345678"),400);
        request(target,"PATCH","/api/v1/users/me/phone-number",Map.of("phoneNumber",""),200);
        assertThat(data(search(viewer,"+85512345678",0,20,200)).get("content").size()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id=UUID_TO_BIN(?) and phone_number is null and status='ACTIVE'",Integer.class,target.id())).isEqualTo(1);
    }
    @Test void discoveryPaginationExclusionsBoundsAndAuthentication() throws Exception {
        var viewer=account();var a=account();var b=account();var deleted=account();String name="Find "+key();
        for(var user:List.of(viewer,a,b,deleted)) jdbc.update("update users set display_name=? where id=UUID_TO_BIN(?)",name,user.id());
        users.softDelete(UUID.fromString(deleted.id()),UUID.fromString(deleted.id()));
        var page0=data(search(viewer,name,0,1,200));var page1=data(search(viewer,name,1,1,200));
        assertThat(page0.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page0.get("last").asBoolean()).isFalse();assertThat(page1.get("last").asBoolean()).isTrue();
        assertThat(Set.of(page0.get("content").get(0).get("id").asText(),page1.get("content").get(0).get("id").asText()))
            .containsExactlyInAnyOrder(a.id(),b.id());
        search(viewer,"ab",0,20,400);search(viewer,"x".repeat(255),0,20,400);
        search(viewer,name,0,51,400);search(viewer,name,10,20,400);search(viewer,name,-1,20,400);
        mvc.perform(get("/api/v1/users/search").param("q",name)).andExpect(status().isUnauthorized());
        search(deleted,name,0,20,401);
    }
    @Test void searchLimitsAreSharedAtomicAndResetAcrossWindows() throws Exception {
        var viewer=account();search(viewer,"nobody",0,20,200);
        jdbc.update("update user_search_limits set minute_window=FLOOR(UNIX_TIMESTAMP()/60),minute_count=29 where user_id=UUID_TO_BIN(?)",viewer.id());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> call=()->{start.await();return mvc.perform(get("/api/v1/users/search").param("q","nobody")
                .header("Authorization","Bearer "+viewer.token())).andReturn().getResponse().getStatus();};
            var first=pool.submit(call);var second=pool.submit(call);start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,429);
        }finally{pool.shutdownNow();}
        jdbc.update("update user_search_limits set minute_window=minute_window-1 where user_id=UUID_TO_BIN(?)",viewer.id());
        search(viewer,"nobody",0,20,200);
        jdbc.update("update user_search_limits set day_count=200 where user_id=UUID_TO_BIN(?)",viewer.id());
        var limited=search(viewer,"nobody",0,20,429);
        assertThat(limited.getResponse().getContentAsString()).contains("SEARCH_RATE_LIMITED");
        jdbc.update("update user_search_limits set day_window=day_window-1 where user_id=UUID_TO_BIN(?)",viewer.id());
        search(viewer,"nobody",0,20,200);
        // An unrelated authenticated account has its own allowance.
        search(account(),"nobody",0,20,200);
    }

    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void groupEntityFieldsRoundTripWithoutChangingPersonalDefaults() {
        var user=new com.qlink.features.user.entity.User();
        user.setDisplayName("Entity fixture");entityManager.persist(user);
        var personal=new com.qlink.features.chat.entity.ChatRoom();
        assertThat(personal.getType()).isEqualTo(com.qlink.features.chat.enums.ChatRoomType.PERSONAL);
        assertThat(personal.getMessageHistoryVisibility()).isEqualTo(com.qlink.features.chat.enums.MessageHistoryVisibility.VIEW_ALL_MESSAGES);
        assertThat(personal.getGroupName()).isNull();
        assertThat(new com.qlink.features.chat.entity.ChatRoomMember().getRole()).isEqualTo(com.qlink.features.chat.enums.ChatRoomMemberRole.MEMBER);
        for(var type:List.of(com.qlink.features.chat.enums.ChatRoomType.GROUP_PRIVATE,com.qlink.features.chat.enums.ChatRoomType.GROUP_PUBLIC)) {
            var room=new com.qlink.features.chat.entity.ChatRoom();
            room.setType(type);room.setCreatorId(user.getId());room.setGroupName("Entity test group");
            room.setMessageHistoryVisibility(com.qlink.features.chat.enums.MessageHistoryVisibility.VIEW_AFTER_JOIN);
            entityManager.persist(room);
            var member=new com.qlink.features.chat.entity.ChatRoomMember();
            member.setRoomId(room.getId());member.setUserId(user.getId());member.setJoinedAt(Instant.now());
            member.setRole(com.qlink.features.chat.enums.ChatRoomMemberRole.OWNER);entityManager.persist(member);
            entityManager.flush();entityManager.clear();
            var loaded=entityManager.find(com.qlink.features.chat.entity.ChatRoom.class,room.getId());
            assertThat(loaded.getType()).isEqualTo(type);
            assertThat(loaded.getPersonalPairKey()).isNull();
            assertThat(loaded.getGroupName()).isEqualTo("Entity test group");
            assertThat(loaded.getMessageHistoryVisibility()).isEqualTo(com.qlink.features.chat.enums.MessageHistoryVisibility.VIEW_AFTER_JOIN);
            assertThat(entityManager.find(com.qlink.features.chat.entity.ChatRoomMember.class,member.getId()).getRole()).isEqualTo(com.qlink.features.chat.enums.ChatRoomMemberRole.OWNER);
            loaded.softDelete(user.getId());entityManager.flush();entityManager.clear();
            assertThat(entityManager.find(com.qlink.features.chat.entity.ChatRoom.class,room.getId())).isNull();
        }
    }

    @Test void groupsCreateValidateAuthorizeAndSend() throws Exception {
        var a=account();var b=account();var c=account();var outsider=account();
        send(a,b,key(),"contact B");send(a,c,key(),"contact C");
        var contacts=data(request(a,"GET","/api/v1/contacts?size=1",null,200));
        assertThat(contacts.get("totalElements").asInt()).isEqualTo(2);
        assertThat(contacts.get("content").get(0).has("email")).isFalse();
        mvc.perform(get("/api/v1/contacts")).andExpect(status().isUnauthorized());
        for(String type:List.of("DEFAULT","GROUP_PRIVATE","GROUP_PUBLIC")) {
            Map<String,Object> payload=new HashMap<>();
            payload.put("name","  Friends  ");payload.put("memberIds",List.of(b.id(),c.id()));
            if(!type.equals("DEFAULT"))payload.put("type",type);
            var group=data(request(a,"POST","/api/v1/chat/groups",payload,200));
            String room=group.get("id").asText();
            assertThat(group.get("name").asText()).isEqualTo("Friends");
            assertThat(group.get("type").asText()).isEqualTo(type.equals("DEFAULT")?"GROUP_PRIVATE":type);
            assertThat(group.get("messageHistoryVisibility").asText()).isEqualTo("VIEW_AFTER_JOIN");
            assertThat(group.get("memberCount").asInt()).isEqualTo(3);
            assertThat(group.get("lastMessage").isNull()).isTrue();
            var members=data(request(b,"GET","/api/v1/chat/groups/"+room+"/members",null,200));
            int owners=0;
            for(var member:members) {
                assertThat(member.has("email")).isFalse();assertThat(member.has("phoneNumber")).isFalse();
                if(member.get("id").asText().equals(a.id())) {assertThat(member.get("role").asText()).isEqualTo("OWNER");owners++;}
                else assertThat(member.get("role").asText()).isEqualTo("MEMBER");
            }
            assertThat(owners).isEqualTo(1);
            assertThat(data(request(b,"GET","/api/v1/contacts",null,200)).get("totalElements").asInt()).isEqualTo(1);
            request(b,"POST","/api/v1/chat/groups",Map.of("name","Group-only relationship","memberIds",List.of(c.id())),400);
            request(outsider,"GET","/api/v1/chat/groups/"+room,null,403);
            request(outsider,"GET","/api/v1/chat/groups/"+room+"/members",null,403);
            request(outsider,"GET","/api/v1/chat/rooms/"+room+"/messages",null,403);
            var message=Map.of("clientMessageId",key(),"content","Group hello");
            request(outsider,"POST","/api/v1/chat/rooms/"+room+"/messages",message,403);
            var sent=data(request(b,"POST","/api/v1/chat/rooms/"+room+"/messages",message,200));
            var retry=data(request(b,"POST","/api/v1/chat/rooms/"+room+"/messages",message,200));
            assertThat(retry.get("message").get("id")).isEqualTo(sent.get("message").get("id"));
            assertThat(sent.get("message").get("senderDisplayName").asText()).isEqualTo("User");
            for(var member:List.of(a,b,c)) {
                assertThat(data(request(member,"GET","/api/v1/chat/rooms/"+room+"/messages",null,200)).get("items").size()).isEqualTo(1);
                assertThat(data(request(member,"GET","/api/v1/chat/rooms",null,200)).toString()).contains(room);
            }
        }
        long before=jdbc.queryForObject("select count(*) from chat_rooms",Long.class);
        for(var ids:List.of(List.of(b.id(),b.id()),List.of(a.id()),List.of(outsider.id()),List.of(key()),List.<String>of())) {
            request(a,"POST","/api/v1/chat/groups",Map.of("name","Invalid","memberIds",ids),400);
        }
        for(String name:List.of("   ","x".repeat(101)))request(a,"POST","/api/v1/chat/groups",Map.of("name",name,"memberIds",List.of(b.id())),400);
        request(a,"POST","/api/v1/chat/groups",Map.of("name","Invalid","type","PERSONAL","memberIds",List.of(b.id())),400);
        assertThat(jdbc.queryForObject("select count(*) from chat_rooms",Long.class)).isEqualTo(before);
        String personal=send(a,b,key(),"personal").get("room").get("id").asText();
        request(a,"GET","/api/v1/chat/groups/"+personal,null,400);
        users.softDelete(UUID.fromString(c.id()),UUID.fromString(c.id()));
        assertThat(data(request(a,"GET","/api/v1/contacts",null,200)).get("totalElements").asInt()).isEqualTo(1);
        request(a,"POST","/api/v1/chat/groups",Map.of("name","Invalid","memberIds",List.of(c.id())),400);
    }

    @Test void groupHistoryBoundaryPaginationAndPreviewAreEnforced() throws Exception {
        var a=account();var b=account();send(a,b,key(),"contact");
        for(String policy:List.of("VIEW_ALL_MESSAGES","VIEW_AFTER_JOIN")) {
            var group=data(request(a,"POST","/api/v1/chat/groups",Map.of("name","History","messageHistoryVisibility",policy,"memberIds",List.of(b.id())),200));
            String room=group.get("id").asText();
            jdbc.update("update chat_room_members set joined_at='2026-01-02 00:00:00.000000' where room_id=UUID_TO_BIN(?)",room);
            for(int n=0;n<3;n++) {
                var sent=data(request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","message "+n),200));
                jdbc.update("update chat_messages set created_at=? where id=UUID_TO_BIN(?)",
                    List.of("2026-01-01 23:59:59.999999","2026-01-02 00:00:00.000000","2026-01-02 00:00:00.000001").get(n),
                    sent.get("message").get("id").asText());
            }
            var result=data(request(b,"GET","/api/v1/chat/rooms/"+room+"/messages?size=1",null,200));
            int count=0;
            while(true) {
                count+=result.get("items").size();
                if(result.get("nextCursor").isNull())break;
                result=data(request(b,"GET","/api/v1/chat/rooms/"+room+"/messages?size=1&cursor="+result.get("nextCursor").asText(),null,200));
            }
            assertThat(count).isEqualTo(policy.equals("VIEW_ALL_MESSAGES")?3:2);
            jdbc.update("update chat_room_members set joined_at='2026-01-03 00:00:00' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
            var detail=data(request(b,"GET","/api/v1/chat/groups/"+room,null,200));
            assertThat(detail.get("lastMessage").isNull()).isEqualTo(policy.equals("VIEW_AFTER_JOIN"));
            jdbc.update("update chat_room_members set deleted_at=UTC_TIMESTAMP(6) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
            request(b,"GET","/api/v1/chat/groups/"+room,null,403);
            request(b,"GET","/api/v1/chat/rooms/"+room+"/messages",null,403);
            jdbc.update("update chat_rooms set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",room);
            request(a,"GET","/api/v1/chat/groups/"+room,null,404);
        }
    }

    @Test void groupCreationRollsBackWhenMembershipInsertFails() throws Exception {
        var a=account();var b=account();send(a,b,key(),"contact");
        long before=jdbc.queryForObject("select count(*) from chat_rooms",Long.class);
        jdbc.execute("ALTER TABLE chat_room_members ADD CONSTRAINT test_group_failure CHECK (NOT (role=\'OWNER\' AND user_id=X\'"+a.id().replace("-","")+"\'))" );
        try {
            request(a,"POST","/api/v1/chat/groups",Map.of("name","Rollback","memberIds",List.of(b.id())),409);
            assertThat(jdbc.queryForObject("select count(*) from chat_rooms",Long.class)).isEqualTo(before);
        } finally {jdbc.execute("ALTER TABLE chat_room_members DROP CHECK test_group_failure");}
    }

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired org.springframework.messaging.simp.SimpMessagingTemplate broker;
    @Autowired org.springframework.messaging.simp.user.SimpUserRegistry socketUsers;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("accessJwtDecoder") JwtDecoder accessDecoder;

    class Wire implements java.net.http.WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        final StringBuilder buffer=new StringBuilder();
        java.net.http.WebSocket socket;
        Wire(String token) throws Exception {
            socket=java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(java.net.URI.create("ws://localhost:"+port+"/ws"),this).get(10,TimeUnit.SECONDS);
            send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n"+
                (token==null?"":"Authorization:Bearer "+token+"\n")+"\n");
        }
        @Override public void onOpen(java.net.http.WebSocket socket) {socket.request(1);}
        @Override public CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence text,boolean last) {
            buffer.append(text);int end;
            while((end=buffer.indexOf("\0"))>=0) {
                String frame=buffer.substring(0,end).stripLeading();buffer.delete(0,end+1);
                if(!frame.isBlank())frames.add(frame);
            }
            socket.request(1);return null;
        }
        void send(String frame) {socket.sendText(frame+"\0",true).join();}
        String next() throws Exception {
            String frame=frames.poll(5,TimeUnit.SECONDS);assertThat(frame).isNotNull();return frame;
        }
        JsonNode event() throws Exception {
            String frame=next();assertThat(frame).startsWith("MESSAGE");
            return json.readTree(frame.substring(frame.indexOf("\n\n")+2));
        }
        void subscribe(Account account) throws Exception {
            assertThat(next()).startsWith("CONNECTED");
            send("SUBSCRIBE\nid:chat\ndestination:/user/queue/chat-events\nack:auto\nreceipt:chat-ready\n\n");
            assertThat(next()).startsWith("RECEIPT").contains("receipt-id:chat-ready");
        }

        void quiet() throws Exception {assertThat(frames.poll(250,TimeUnit.MILLISECONDS)).isNull();}
        @Override public void close() {socket.abort();}
    }

    String uploadVoice(Account owner) throws Exception {
        var file=new org.springframework.mock.web.MockMultipartFile("file","voice.wav","audio/wav",com.qlink.storage.VoiceFilesTest.wav(1000));
        return data(mvc.perform(multipart("/api/v1/storage/files").file(file).param("category","AUDIO").param("voice","true")
            .header("Authorization","Bearer "+owner.token())).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    String uploadGroupImage(Account owner) throws Exception {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=");
        var file=new org.springframework.mock.web.MockMultipartFile("file","group.png","image/png",png);
        return data(mvc.perform(multipart("/api/v1/storage/files").file(file)
            .header("Authorization","Bearer "+owner.token())).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    @Test void fileAttachmentsUseExistingRealtimeAndGroupHistoryAuthorization() throws Exception {
        var a=account(); var b=account(); send(a,b,key(),"contact");
        String room=data(request(a,"POST","/api/v1/chat/groups",Map.of("name","Attachments","memberIds",List.of(b.id())),200)).get("id").asText();
        var upload=new org.springframework.mock.web.MockMultipartFile("file","report.txt","text/plain","Shared report".getBytes());
        String file=data(mvc.perform(multipart("/api/v1/storage/files").file(upload)
            .header("Authorization","Bearer "+a.token())).andExpect(status().isCreated()).andReturn()).get("id").asText();
        Map<String,Object> payload=Map.of("clientMessageId",key(),"type","FILE","fileId",file);
        try(var wire=new Wire(b.token())) {
            wire.subscribe(b);
            var sent=data(request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",payload,200));
            var received=wire.event().get("message");
            assertThat(received.get("id")).isEqualTo(sent.at("/message/id"));
            assertThat(received.get("fileId")).isEqualTo(sent.at("/message/fileId"));
            assertThat(received.get("attachment")).isEqualTo(sent.at("/message/attachment"));
            // MySQL rounds DATETIME(6) on commit; HTTP's in-memory timestamp can differ by 1 microsecond.
            assertThat(Math.abs(java.time.Duration.between(Instant.parse(received.get("createdAt").asText()),
                Instant.parse(sent.at("/message/createdAt").asText())).toNanos())).isLessThanOrEqualTo(1000);
            assertThat(sent.at("/message/attachment/originalName").asText()).isEqualTo("report.txt");
            request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",payload,200);
            wire.quiet();
            request(b,"GET","/api/v1/storage/files/"+file+"/download",null,200);
            jdbc.update("update chat_room_members set joined_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
            request(b,"GET","/api/v1/storage/files/"+file+"/download",null,404);
        }
    }

    @Test void voiceCreationAccessIdempotencyAndRealtime() throws Exception {
        var a=account(); var b=account(); var outsider=account();
        String file=uploadVoice(a);
        request(b,"GET","/api/v1/storage/files/"+file+"/download",null,404);
        Map<String,Object> payload=new HashMap<>(Map.of("recipientId",b.id(),"clientMessageId",key(),"type","VOICE","fileId",file,"durationMs",1000));
        try(var wire=new Wire(b.token())) {
            wire.subscribe(b);
            var sent=data(request(a,"POST","/api/v1/chat/personal/messages",payload,200));
            assertThat(sent.get("message").get("type").asText()).isEqualTo("VOICE");
            assertThat(sent.get("message").get("durationMs").asLong()).isEqualTo(1000);
            var received=wire.event().get("message");
            assertThat(received.get("canDeleteForEveryone").asBoolean()).isFalse();
            assertThat(sent.at("/message/canDeleteForEveryone").asBoolean()).isTrue();
            for(String field:List.of("id","type","fileId","content","durationMs","audioContentType","audioSizeBytes","revision"))
                assertThat(received.get(field)).isEqualTo(sent.get("message").get(field));
            assertThat(data(request(a,"POST","/api/v1/chat/personal/messages",payload,200)).get("message")).isEqualTo(sent.get("message"));
            wire.quiet();
            request(b,"GET","/api/v1/storage/files/"+file+"/download",null,200);
            request(outsider,"GET","/api/v1/storage/files/"+file+"/download",null,404);
            request(outsider,"POST","/api/v1/chat/rooms/"+sent.get("room").get("id").asText()+"/messages",payload,403);
            mvc.perform(delete("/api/v1/storage/files/"+file).header("Authorization","Bearer "+a.token())).andExpect(status().isBadRequest());
            jdbc.update("update chat_messages set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",sent.get("message").get("id").asText());
            request(a,"GET","/api/v1/storage/files/"+file+"/download",null,404);
            request(b,"GET","/api/v1/storage/files/"+file+"/download",null,404);
        }
        payload.put("clientMessageId",key()); payload.put("fileId",key());
        request(a,"POST","/api/v1/chat/personal/messages",payload,404);
        payload.put("fileId",uploadVoice(outsider));
        request(a,"POST","/api/v1/chat/personal/messages",payload,404);
        payload.put("fileId",uploadVoice(a)); payload.put("durationMs",10000);
        request(a,"POST","/api/v1/chat/personal/messages",payload,400);
        for(byte[] bytes:List.of(new byte[100],com.qlink.storage.VoiceFilesTest.wav(130000))) {
            var invalid=new org.springframework.mock.web.MockMultipartFile("file","voice.wav","audio/wav",bytes);
            mvc.perform(multipart("/api/v1/storage/files").file(invalid).param("voice","true").header("Authorization","Bearer "+a.token()))
                .andExpect(status().is(bytes.length>4000000?413:415));
        }
    }

    @Test void voiceHistoryMembershipAndCleanup() throws Exception {
        var a=account(); var b=account(); send(a,b,key(),"contact");
        String room=data(request(a,"POST","/api/v1/chat/groups",Map.of("name","Voice","memberIds",List.of(b.id())),200)).get("id").asText();
        String file=uploadVoice(a);
        var sent=data(request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"type","VOICE","fileId",file,"durationMs",1000),200));
        String id=sent.get("message").get("id").asText();
        request(b,"GET","/api/v1/storage/files/"+file+"/download",null,200);
        jdbc.update("update chat_room_members set joined_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
        request(b,"GET","/api/v1/storage/files/"+file+"/download",null,404);
        jdbc.update("update chat_room_members set deleted_at=UTC_TIMESTAMP(6) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
        request(b,"GET","/api/v1/storage/files/"+file+"/download",null,404);
        String orphan=uploadVoice(a);
        jdbc.update("update stored_files set created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 25 HOUR) where id=UUID_TO_BIN(?)",orphan);
        voiceCleanup.run();
        request(a,"GET","/api/v1/storage/files/"+orphan,null,404);
        request(a,"GET","/api/v1/storage/files/"+file,null,200);
        jdbc.update("update chat_messages set deleted_at=UTC_TIMESTAMP(6) where id=UUID_TO_BIN(?)",id);
        voiceCleanup.run();
        assertThat(jdbc.queryForObject("select status from stored_files where id=UUID_TO_BIN(?)",String.class,file)).isEqualTo("DELETED");
    }

    @Test void nativeStompRejectsInvalidExpiredRevokedAndForgedOperations() throws Exception {
        var a=account();
        for(String token:List.of("invalid",""))try(var wire=new Wire(token)) {
            assertThat(wire.next()).startsWith("ERROR").contains("Connection not authorized").doesNotContain(token.isEmpty()?"secret-value":token);
        }
        try(var wire=new Wire(null)) {assertThat(wire.next()).startsWith("ERROR");}
        var valid=accessDecoder.decode(a.token());
        String expired=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
            JwtClaimsSet.builder().issuer(valid.getClaimAsString("iss")).subject(a.id())
            .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(120))
            .claim("sid",valid.getClaimAsString("sid")).claim("token_use","access").build())).getTokenValue();
        try(var wire=new Wire(expired)) {assertThat(wire.next()).startsWith("ERROR");}
        for(String frame:List.of(
            "SUBSCRIBE\nid:x\ndestination:/queue/chat-events\n\n",
            "SUBSCRIBE\nid:x\ndestination:/user/another/queue/chat-events\n\n",
            "SUBSCRIBE\nid:x\ndestination:/topic/chat/room\n\n",
            "SEND\ndestination:/queue/chat-events\n\nforged",
            "SEND\ndestination:/app/messages\n\nforged")) {
            try(var wire=new Wire(a.token())) {assertThat(wire.next()).startsWith("CONNECTED");wire.send(frame);assertThat(wire.next()).startsWith("ERROR");}
        }
        request(a,"POST","/api/v1/auth/signout",null,200);
        try(var wire=new Wire(a.token())) {assertThat(wire.next()).startsWith("ERROR");}
    }

    @Test void nativeStompPersonalFirstMessageCommitIdempotencyAndReconnect() throws Exception {
        var a=account();var b=account();var outsider=account();
        String room, messageId;
        try(var wa=new Wire(a.token());var wb=new Wire(b.token());var wo=new Wire(outsider.token())) {
            wa.subscribe(a);wb.subscribe(b);wo.subscribe(outsider);
            String key=key();
            var sent=send(a,b,key,"Hello over REST");
            room=sent.get("room").get("id").asText();messageId=sent.get("message").get("id").asText();
            for(var wire:List.of(wa,wb)) {
                var event=wire.event();
                assertThat(event.get("type").asText()).isEqualTo("MESSAGE_CREATED");
                assertThat(event.get("message").get("id").asText()).isEqualTo(messageId);
                assertThat(event.get("eventId").asText()).isEqualTo(messageId);
                assertThat(event.get("message").get("createdAt").isTextual()).isTrue();
                assertThatCode(() -> Instant.parse(event.get("message").get("createdAt").asText())).doesNotThrowAnyException();
                assertThat(event.get("roomId").asText()).isEqualTo(room);
                assertThat(jdbc.queryForObject("select count(*) from chat_messages where id=UUID_TO_BIN(?)",Integer.class,messageId)).isEqualTo(1);
            }
            wo.quiet();
            send(a,b,key,"Hello over REST");wa.quiet();wb.quiet();
            // An event raised inside a rolled-back transaction must never reach subscribers.
            new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                events.publishEvent(new com.qlink.features.chat.realtime.MessageCommitted(UUID.fromString(messageId)));
                status.setRollbackOnly();return null;
            });
            wa.quiet();wb.quiet();
            request(b,"POST","/api/v1/auth/signout",null,200);
            send(a,b,key(),"Revoked socket must not receive");
            wa.event();wb.quiet();
        }
        var c=account();send(a,c,key(),"initial");
        String cRoom=data(request(c,"GET","/api/v1/chat/rooms",null,200)).get("content").get(0).get("id").asText();
        try(var wc=new Wire(c.token())) {wc.subscribe(c);}
        send(a,c,key(),"missed while disconnected");
        try(var wc=new Wire(c.token())) {
            wc.subscribe(c);
            var history=data(request(c,"GET","/api/v1/chat/rooms/"+cRoom+"/messages",null,200));
            assertThat(history.get("items").size()).isEqualTo(2);
            assertThat(history.get("items").get(0).get("content").asText()).isEqualTo("missed while disconnected");
            send(a,c,key(),"live after reconnect");
            assertThat(wc.event().get("message").get("content").asText()).isEqualTo("live after reconnect");
        }
    }

    @Test void nativeStompGroupsOnlyDeliverToAuthorizedActiveMembers() throws Exception {
        var a=account();var b=account();var c=account();var outsider=account();
        send(a,b,key(),"contact");send(a,c,key(),"contact");
        try(var wa=new Wire(a.token());var wb=new Wire(b.token());var wc=new Wire(c.token());var wo=new Wire(outsider.token())) {
            wa.subscribe(a);wb.subscribe(b);wc.subscribe(c);wo.subscribe(outsider);
            for(String type:List.of("GROUP_PRIVATE","GROUP_PUBLIC")) {
                String room=data(request(a,"POST","/api/v1/chat/groups",Map.of("name","Realtime","type",type,"memberIds",List.of(b.id(),c.id())),200)).get("id").asText();
                for(var wire:List.of(wa,wb,wc)) assertThat(wire.event().get("type").asText()).isEqualTo("GROUP_UPDATED");
                request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","Group live"),200);
                for(var wire:List.of(wa,wb,wc)) {
                    var event=wire.event();assertThat(event.get("roomType").asText()).isEqualTo(type);
                    assertThat(event.get("message").get("senderDisplayName").asText()).isEqualTo("User");
                }
                wo.quiet();
                // A future membership start cannot receive older messages through the event path.
                jdbc.update("update chat_room_members set joined_at='2099-01-01' where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,b.id());
                jdbc.update("update chat_room_members set deleted_at=UTC_TIMESTAMP(6) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?)",room,c.id());
                request(a,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","Restricted"),200);
                wa.event();wb.quiet();wc.quiet();wo.quiet();
            }
            users.softDelete(UUID.fromString(b.id()),UUID.fromString(b.id()));
            send(a,c,key(),"Active member only");wa.event();wc.event();wb.quiet();
        }
    }

    String managedGroup(Account owner, Account initialMember) throws Exception {
        return managedGroup(owner,initialMember,"VIEW_AFTER_JOIN");
    }
    String managedGroup(Account owner, Account initialMember, String historyVisibility) throws Exception {
        send(owner,initialMember,key(),"contact");
        return data(request(owner,"POST","/api/v1/chat/groups",
            Map.of("name","Managed","memberIds",List.of(initialMember.id()),"messageHistoryVisibility",historyVisibility),200)).get("id").asText();
    }

    @Test void groupOwnerCoOwnerAndMemberActionsAreEnforced() throws Exception {
        var owner=account(); var coOwner=account(); var member=account(); var invitee=account(); var stranger=account();
        String room=managedGroup(owner,coOwner), path="/api/v1/chat/groups/"+room;
        request(owner,"POST",path+"/members",Map.of("memberIds",List.of(member.id())),200);
        request(owner,"PATCH",path+"/members/"+coOwner.id()+"/role",Map.of("role","CO_OWNER"),200);

        request(member,"PATCH",path,Map.of("name","Forbidden","description",""),403);
        request(member,"PATCH",path+"/members/"+coOwner.id()+"/role",Map.of("role","CO_OWNER"),403);
        request(member,"POST",path+"/ownership",Map.of("userId",coOwner.id()),403);
        request(member,"PATCH",path+"/permissions",Map.of("membersCanSend",false,"membersCanInvite",true),403);
        request(member,"DELETE",path,null,403);
        request(stranger,"GET",path,null,403);

        request(coOwner,"PATCH",path,Map.of("name","Co-owner edit","description","Group notes"),200);
        request(coOwner,"DELETE",path+"/members/"+owner.id(),null,403);
        request(coOwner,"PATCH",path+"/members/"+member.id()+"/role",Map.of("role","CO_OWNER"),403);
        request(coOwner,"POST",path+"/ownership",Map.of("userId",member.id()),403);
        request(coOwner,"PATCH",path+"/permissions",Map.of("membersCanSend",false,"membersCanInvite",true),403);
        request(coOwner,"DELETE",path,null,403);
        request(coOwner,"DELETE",path+"/members/"+member.id(),null,200);
        request(member,"GET","/api/v1/chat/rooms/"+room+"/messages",null,403);

        request(owner,"POST",path+"/members",Map.of("memberIds",List.of(member.id())),200);
        request(member,"POST",path+"/members",Map.of("memberIds",List.of(invitee.id())),403);
        request(owner,"PATCH",path+"/permissions",Map.of("membersCanSend",false,"membersCanInvite",true),200);
        request(member,"POST",path+"/members",Map.of("memberIds",List.of(invitee.id())),200);
        request(member,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","denied"),403);
        request(owner,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","owner can send"),200);
        request(coOwner,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","co-owner can send"),200);

        request(owner,"POST",path+"/leave",null,400);
        request(owner,"PATCH",path+"/members/"+coOwner.id()+"/role",Map.of("role","MEMBER"),200);
        request(coOwner,"PATCH",path,Map.of("name","Demoted"),403);
        request(owner,"POST",path+"/ownership",Map.of("userId",coOwner.id()),200);
        assertThat(data(request(coOwner,"GET",path,null,200)).get("myRole").asText()).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select count(*) from chat_room_members where room_id=UUID_TO_BIN(?) and deleted_at is null and role='OWNER'",Integer.class,room)).isEqualTo(1);
        request(coOwner,"DELETE",path,null,200);
        request(owner,"GET",path,null,404);
    }

    @Test void concurrentOwnershipTransfersCommitOneOwner() throws Exception {
        var owner=account(); var first=account(); var second=account();
        String room=managedGroup(owner,first), path="/api/v1/chat/groups/"+room;
        request(owner,"POST",path+"/members",Map.of("memberIds",List.of(second.id())),200);
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<Integer>>();
            for(var target:List.of(first,second)) tasks.add(pool.submit(()->{
                start.await();
                return mvc.perform(post(path+"/ownership").header("Authorization","Bearer "+owner.token())
                    .contentType("application/json").content(body(Map.of("userId",target.id())))).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            assertThat(List.of(tasks.get(0).get(30,TimeUnit.SECONDS),tasks.get(1).get(30,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200,403);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from chat_room_members where room_id=UUID_TO_BIN(?) and deleted_at is null and role='OWNER'",Integer.class,room)).isEqualTo(1);
    }

    @Test void rejoiningStartsANewHistoryBoundaryAndIdempotentRetryRespectsIt() throws Exception {
        for(String visibility:List.of("VIEW_AFTER_JOIN","VIEW_ALL_MESSAGES")) {
            var owner=account(); var member=account();
            String room=managedGroup(owner,member,visibility), path="/api/v1/chat/groups/"+room;
            String oldKey=key();
            request(member,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",oldKey,"content","Earlier message"),200);
            request(member,"POST",path+"/leave",null,200);
            request(owner,"POST",path+"/members",Map.of("memberIds",List.of(member.id())),200);
            var history=data(request(member,"GET","/api/v1/chat/rooms/"+room+"/messages",null,200));
            assertThat(history.get("items").size()).isEqualTo(visibility.equals("VIEW_ALL_MESSAGES")?1:0);
            request(member,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",oldKey,"content","Earlier message"),visibility.equals("VIEW_ALL_MESSAGES")?200:403);
            request(member,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","After rejoining"),200);
            var after=data(request(member,"GET","/api/v1/chat/rooms/"+room+"/messages",null,200));
            assertThat(after.get("items").size()).isEqualTo(visibility.equals("VIEW_ALL_MESSAGES")?2:1);
            assertThat(after.get("items").get(0).get("content").asText()).isEqualTo("After rejoining");
        }
    }

    @Test void groupAvatarUpdatesAreRoleCheckedAndPrivateToActiveMembers() throws Exception {
        var owner=account(); var member=account(); var outsider=account();
        String room=managedGroup(owner,member), path="/api/v1/chat/groups/"+room;
        String first=uploadGroupImage(owner);
        request(member,"PUT",path+"/avatar",Map.of("fileId",first),403);
        request(owner,"PUT",path+"/avatar",Map.of("fileId",first),200);
        request(member,"GET","/api/v1/storage/files/"+first+"/download",null,200);
        request(outsider,"GET","/api/v1/storage/files/"+first+"/download",null,404);
        request(owner,"DELETE","/api/v1/storage/files/"+first,null,400);

        request(owner,"PATCH",path+"/members/"+member.id()+"/role",Map.of("role","CO_OWNER"),200);
        String second=uploadGroupImage(member);
        request(member,"PUT",path+"/avatar",Map.of("fileId",second),200);
        request(owner,"GET","/api/v1/storage/files/"+first+"/download",null,404);
        request(owner,"GET","/api/v1/storage/files/"+second+"/download",null,200);
        request(owner,"DELETE",path+"/members/"+member.id(),null,200);
        request(member,"GET","/api/v1/storage/files/"+second+"/download",null,404);
        request(owner,"DELETE",path+"/avatar",null,200);
        request(owner,"GET","/api/v1/storage/files/"+second+"/download",null,404);
    }

    @Test void messageReplyEditDeleteAndPrivateHidingPersist() throws Exception {
        var a=account();var b=account();var outsider=account();
        var sent=send(a,b,key(),"Original");String room=sent.get("room").get("id").asText();
        String base="/api/v1/chat/rooms/"+room+"/messages", id=sent.get("message").get("id").asText();
        var reply=data(request(b,"POST",base,Map.of("clientMessageId",key(),"content","Reply","replyToId",id),200)).get("message");
        assertThat(reply.get("reply").get("content").asText()).isEqualTo("Original");
        request(outsider,"PATCH",base+"/"+id,Map.of("content","Intrude","revision",0),403);
        request(b,"PATCH",base+"/"+id,Map.of("content","Intrude","revision",0),403);
        request(a,"PATCH",base+"/"+id,Map.of("content"," ","revision",0),400);
        var edited=data(request(a,"PATCH",base+"/"+id,Map.of("content","Edited","revision",0),200));
        assertThat(edited.get("revision").asInt()).isEqualTo(1);
        assertThat(edited.get("editedAt").isNull()).isFalse();
        request(a,"PATCH",base+"/"+id,Map.of("content","Stale","revision",0),409);
        request(b,"DELETE",base+"/"+id+"?everyone=true",null,403);
        request(b,"DELETE",base+"/"+id,null,200);
        request(b,"DELETE",base+"/"+id,null,200);
        var hidden=data(request(b,"GET",base,null,200)).get("items");
        assertThat(hidden.get(0).get("reply").get("unavailable").asBoolean()).isTrue();
        assertThat(hidden.get(1).get("hidden").asBoolean()).isTrue();
        assertThat(hidden.get(1).get("content").asText()).isEmpty();
        assertThat(data(request(a,"GET",base,null,200)).get("items").get(1).get("content").asText()).isEqualTo("Edited");
        request(b,"POST",base,Map.of("clientMessageId",key(),"content","Hidden reply","replyToId",id),400);
        request(a,"DELETE",base+"/"+id+"?everyone=true",null,200);
        var history=data(request(a,"GET",base,null,200)).get("items");
        assertThat(history.get(0).get("reply").get("unavailable").asBoolean()).isTrue();
        assertThat(history.get(1).get("removedAt").isNull()).isFalse();
        assertThat(history.get(1).get("content").asText()).isEmpty();
        request(a,"PATCH",base+"/"+id,Map.of("content","Restore","revision",2),400);
        var other=send(a,outsider,key(),"Other room").get("message").get("id").asText();
        request(a,"POST",base,Map.of("clientMessageId",key(),"content","Cross-room","replyToId",other),404);
    }

    @Test void groupModerationAndReplyHistoryBoundaries() throws Exception {
        var owner=account();var co=account();var member=account();var peer=account();
        String room=managedGroup(owner,co), group="/api/v1/chat/groups/"+room, base="/api/v1/chat/rooms/"+room+"/messages";
        request(owner,"POST",group+"/members",Map.of("memberIds",List.of(member.id(),peer.id())),200);
        request(owner,"PATCH",group+"/members/"+co.id()+"/role",Map.of("role","CO_OWNER"),200);
        request(owner,"PATCH",group+"/members/"+peer.id()+"/role",Map.of("role","CO_OWNER"),200);
        var ownerId=data(request(owner,"POST",base,Map.of("clientMessageId",key(),"content","Owner"),200)).get("message").get("id").asText();
        var peerId=data(request(peer,"POST",base,Map.of("clientMessageId",key(),"content","Peer"),200)).get("message").get("id").asText();
        var memberId=data(request(member,"POST",base,Map.of("clientMessageId",key(),"content","Member"),200)).get("message").get("id").asText();
        request(co,"DELETE",base+"/"+ownerId+"?everyone=true",null,403);
        request(co,"DELETE",base+"/"+peerId+"?everyone=true",null,403);
        request(member,"DELETE",base+"/"+peerId+"?everyone=true",null,403);
        request(co,"DELETE",base+"/"+memberId+"?everyone=true",null,200);
        request(owner,"DELETE",base+"/"+peerId+"?everyone=true",null,200);
        jdbc.update("update chat_room_members set joined_at=UTC_TIMESTAMP(6) where room_id=UUID_TO_BIN(?) and user_id=UUID_TO_BIN(?) and deleted_at is null",room,member.id());
        request(member,"POST",base,Map.of("clientMessageId",key(),"content","Hidden","replyToId",ownerId),404);
        request(member,"DELETE",base+"/"+ownerId,null,404);
        request(member,"PATCH",base+"/"+memberId,Map.of("content","Hidden","revision",1),404);
        request(owner,"POST",base,Map.of("clientMessageId",key(),"content","Visible reply","replyToId",ownerId),200);
        var preview=data(request(member,"GET",base,null,200)).get("items").get(0).get("reply");
        assertThat(preview.get("unavailable").asBoolean()).isTrue();
        assertThat(preview.get("senderDisplayName").isNull()).isTrue();
    }

    @Test void actionsSynchronizeTwoDevicesAndHideOnlyRequestingAccount() throws Exception {
        var a=account();var b=account();
        var sent=send(a,b,key(),"Original");String room=sent.get("room").get("id").asText(), id=sent.get("message").get("id").asText();
        String path="/api/v1/chat/rooms/"+room+"/messages/"+id;
        try(var first=new Wire(a.token());var second=new Wire(a.token());var recipient=new Wire(b.token())) {
            first.subscribe(a);second.subscribe(a);recipient.subscribe(b);
            request(a,"PATCH",path,Map.of("content","Changed","revision",0),200);
            for(var wire:List.of(first,second,recipient)) {
                var e=wire.event();assertThat(e.get("type").asText()).isEqualTo("MESSAGE_UPDATED");
                assertThat(e.get("message").get("content").asText()).isEqualTo("Changed");
            }
            request(a,"DELETE",path,null,200);
            for(var wire:List.of(first,second)) assertThat(wire.event().get("message").get("hidden").asBoolean()).isTrue();
            recipient.quiet();
            request(a,"DELETE",path+"?everyone=true",null,200);
            first.event();second.event();
            assertThat(recipient.event().get("message").get("removedAt").isNull()).isFalse();
        }
        var reloaded=data(request(a,"GET","/api/v1/chat/rooms/"+room+"/messages",null,200)).get("items").get(0);
        assertThat(reloaded.get("hidden").asBoolean()).isTrue();
    }

    @Test void concurrentEditsHaveExactlyOneWinner() throws Exception {
        var a=account();var b=account();var sent=send(a,b,key(),"Original");
        String path="/api/v1/chat/rooms/"+sent.get("room").get("id").asText()+"/messages/"+sent.get("message").get("id").asText();
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> edit=() -> {start.await();return mvc.perform(patch(path).header("Authorization","Bearer "+a.token()).contentType("application/json").content(body(Map.of("content",key(),"revision",0)))).andReturn().getResponse().getStatus();};
            var one=pool.submit(edit);var two=pool.submit(edit);start.countDown();
            assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
    }

    @Test void attachmentRepliesAndTombstonesKeepStorageReferencesSafe() throws Exception {
        var a=account();var b=account();String room=send(a,b,key(),"Contact").get("room").get("id").asText();
        String base="/api/v1/chat/rooms/"+room+"/messages";
        for(String type:List.of("IMAGE","VOICE","FILE")) {
            String file;
            if(type.equals("VOICE")) file=uploadVoice(a);
            else if(type.equals("IMAGE")) file=uploadGroupImage(a);
            else {
                var upload=new org.springframework.mock.web.MockMultipartFile("file","report.txt","text/plain","Report".getBytes());
                file=data(mvc.perform(multipart("/api/v1/storage/files").file(upload).header("Authorization","Bearer "+a.token())).andExpect(status().isCreated()).andReturn()).get("id").asText();
            }
            var payload=new HashMap<String,Object>();payload.put("clientMessageId",key());payload.put("type",type);payload.put("fileId",file);
            if(type.equals("VOICE")) payload.put("durationMs",1000);
            String id=data(request(a,"POST",base,payload,200)).get("message").get("id").asText();
            var reply=data(request(b,"POST",base,Map.of("clientMessageId",key(),"content","Reply","replyToId",id),200)).get("message");
            assertThat(reply.get("reply").get("type").asText()).isEqualTo(type);
            request(a,"PATCH",base+"/"+id,Map.of("content","Edit media","revision",0),400);
            request(b,"DELETE",base+"/"+id,null,200);
            request(b,"GET","/api/v1/storage/files/"+file+"/download",null,404);
            request(a,"GET","/api/v1/storage/files/"+file+"/download",null,200);
            var removed=data(request(a,"DELETE",base+"/"+id+"?everyone=true",null,200));
            assertThat(removed.get("fileId").isNull()).isTrue();
            request(a,"GET","/api/v1/storage/files/"+file+"/download",null,404);
            assertThat(jdbc.queryForObject("select BIN_TO_UUID(file_id) from chat_messages where id=UUID_TO_BIN(?)",String.class,id)).isEqualToIgnoringCase(file);
            payload.put("clientMessageId",key());request(a,"POST",base,payload,400);
        }
    }

    @Test void uploadKeySurvivesLostResponsesWithoutDuplicateStoredFiles() throws Exception {
        var a=account();var b=account();String key=key();
        var upload=new org.springframework.mock.web.MockMultipartFile("file","report.txt","text/plain","Retained report".getBytes());
        var first=data(mvc.perform(multipart("/api/v1/storage/files").file(upload).param("uploadKey",key).header("Authorization","Bearer "+a.token())).andExpect(status().isCreated()).andReturn());
        var retry=data(mvc.perform(multipart("/api/v1/storage/files").file(upload).param("uploadKey",key).header("Authorization","Bearer "+a.token())).andExpect(status().isCreated()).andReturn());
        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertThat(jdbc.queryForObject("select count(*) from stored_files where uploaded_by=UUID_TO_BIN(?) and upload_key=UUID_TO_BIN(?)",Integer.class,a.id(),key)).isEqualTo(1);
        assertThat(data(request(a,"GET","/api/v1/storage/files/uploads/"+key,null,200)).get("id")).isEqualTo(first.get("id"));
        request(b,"GET","/api/v1/storage/files/uploads/"+key,null,404);
    }

    @Test void personalReceiptsAreMonotonicAuthorizedAndSynchronized() throws Exception {
        var a=account();var b=account();var outsider=account();
        var sent=send(a,b,key(),"Receipt");String room=sent.at("/room/id").asText(),id=sent.at("/message/id").asText();
        String path="/api/v1/chat/rooms/"+room+"/receipts";
        assertThat(sent.at("/message/recipientCount").asInt()).isEqualTo(1);
        request(outsider,"POST",path,Map.of("messageIds",List.of(id),"seen",true),403);
        try(var first=new Wire(a.token());var second=new Wire(a.token())) {
            first.subscribe(a);second.subscribe(a);
            var delivered=data(request(b,"POST",path,Map.of("messageIds",List.of(id),"seen",false),200));
            assertThat(delivered.get(0).get("delivery").asText()).isEqualTo("DELIVERED");
            for(var wire:List.of(first,second)) assertThat(wire.event().get("type").asText()).isEqualTo("MESSAGE_RECEIPT");
            request(b,"POST",path,Map.of("messageIds",List.of(id),"seen",false),200);first.quiet();second.quiet();
            var seen=data(request(b,"POST",path,Map.of("messageIds",List.of(id),"seen",true),200));
            assertThat(seen.get(0).get("delivery").asText()).isEqualTo("SEEN");first.event();second.event();
            var old=data(request(b,"POST",path,Map.of("messageIds",List.of(id),"seen",false),200));
            assertThat(old.get(0).get("delivery").asText()).isEqualTo("SEEN");first.quiet();second.quiet();
        }
    }
    @Test void groupReceiptsRequireEveryOriginalRecipientAndRespectHistory() throws Exception {
        var owner=account();var a=account();var b=account();var late=account();
        String room=managedGroup(owner,a),group="/api/v1/chat/groups/"+room,base="/api/v1/chat/rooms/"+room;
        request(owner,"POST",group+"/members",Map.of("memberIds",List.of(b.id())),200);
        var sent=data(request(owner,"POST",base+"/messages",Map.of("clientMessageId",key(),"content","Everyone"),200));String id=sent.at("/message/id").asText();
        assertThat(sent.at("/message/recipientCount").asInt()).isEqualTo(2);
        var one=data(request(a,"POST",base+"/receipts",Map.of("messageIds",List.of(id),"seen",true),200));
        assertThat(one.get(0).get("delivery").asText()).isEqualTo("SENT");
        var all=data(request(b,"POST",base+"/receipts",Map.of("messageIds",List.of(id),"seen",true),200));
        assertThat(all.get(0).get("delivery").asText()).isEqualTo("SEEN");
        request(owner,"POST",group+"/members",Map.of("memberIds",List.of(late.id())),200);
        request(late,"POST",base+"/receipts",Map.of("messageIds",List.of(id),"seen",true),404);
        jdbc.update("update chat_rooms set message_history_visibility='VIEW_ALL_MESSAGES' where id=UUID_TO_BIN(?)",room);
        assertThat(data(request(late,"POST",base+"/receipts",Map.of("messageIds",List.of(id),"seen",true),200)).size()).isZero();
        jdbc.update("update chat_rooms set message_history_visibility='VIEW_AFTER_JOIN' where id=UUID_TO_BIN(?)",room);
        request(owner,"DELETE",group+"/members/"+a.id(),null,200);
        request(owner,"POST",group+"/members",Map.of("memberIds",List.of(a.id())),200);
        request(a,"POST",base+"/receipts",Map.of("messageIds",List.of(id),"seen",true),404);
    }

    @Test void lostResponseLookupReturnsCanonicalEditedOrDeletedMessageOnlyToSender() throws Exception {
        var a=account();var b=account();String key=key();var sent=send(a,b,key,"Original");
        String path="/api/v1/chat/rooms/"+sent.at("/room/id").asText()+"/messages/"+sent.at("/message/id").asText();
        request(a,"PATCH",path,Map.of("content","Edited on another device","revision",0),200);
        var found=data(request(a,"GET","/api/v1/chat/messages/client/"+key,null,200));
        assertThat(found.at("/message/content").asText()).isEqualTo("Edited on another device");
        request(b,"GET","/api/v1/chat/messages/client/"+key,null,404);
        request(a,"DELETE",path+"?everyone=true",null,200);
        assertThat(data(request(a,"GET","/api/v1/chat/messages/client/"+key,null,200)).at("/message/removedAt").isNull()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from chat_event_jobs where message_id=UUID_TO_BIN(?)",Integer.class,sent.at("/message/id").asText())).isEqualTo(3);
    }

    @Test void pushDevicesMuteLogoutAndInvalidTokenCleanup() throws Exception {
        var a=account();var b=account();var outsider=account();String deviceA=key(),deviceB=key(),deviceB2=key();
        request(a,"PUT","/api/v1/push/devices/"+deviceA,Map.of("token","sender-device","platform","ANDROID"),200);
        request(b,"PUT","/api/v1/push/devices/"+deviceB,Map.of("token","recipient-device","platform","IOS"),200);
        request(b,"PUT","/api/v1/push/devices/"+deviceB2,Map.of("token","recipient-device-2","platform","ANDROID"),200);
        request(b,"PUT","/api/v1/push/devices/"+deviceB,Map.of("token","recipient-device-refreshed","platform","IOS"),200);
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id=UUID_TO_BIN(?)",Integer.class,b.id())).isEqualTo(2);
        when(pushGateway.enabled()).thenReturn(true);
        var sent=send(a,b,key(),"Private message text never passed to gateway");String room=sent.at("/room/id").asText(),message=sent.at("/message/id").asText();
        assertThat(jdbc.queryForObject("select count(*) from push_deliveries where message_id=UUID_TO_BIN(?)",Integer.class,message)).isEqualTo(2);
        for(String id:jdbc.queryForList("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,message)) push.dispatch(UUID.fromString(id));
        verify(pushGateway,times(2)).send(any(),eq(UUID.fromString(room)),eq(UUID.fromString(message)),eq(UUID.fromString(b.id())),any());
        request(outsider,"PUT","/api/v1/chat/rooms/"+room+"/notifications",Map.of("muted",true),403);
        request(b,"PUT","/api/v1/chat/rooms/"+room+"/notifications",Map.of("muted",true),200);
        assertThat(data(request(b,"GET","/api/v1/chat/rooms/"+room+"/notifications",null,200)).get("muted").asBoolean()).isTrue();
        String muted=send(a,b,key(),"Muted").at("/message/id").asText();
        assertThat(jdbc.queryForObject("select count(*) from push_deliveries where message_id=UUID_TO_BIN(?)",Integer.class,muted)).isZero();
        request(b,"PUT","/api/v1/chat/rooms/"+room+"/notifications",Map.of("muted",false),200);
        doThrow(new com.qlink.features.push.PushGateway.InvalidToken()).when(pushGateway).send(any(),any(),any(),any(),any());
        String invalid=send(a,b,key(),"Invalid devices").at("/message/id").asText();
        for(String id:jdbc.queryForList("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,invalid)) push.dispatch(UUID.fromString(id));
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id=UUID_TO_BIN(?)",Integer.class,b.id())).isZero();
        request(a,"POST","/api/v1/auth/signout",null,200);
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id=UUID_TO_BIN(?)",Integer.class,a.id())).isZero();
    }
    @Test void disabledPushDoesNotQueueAndRegistrationIsAccountScoped() throws Exception {
        var a=account();var b=account();String installation=key(),token="account-switch-"+key();
        request(a,"PUT","/api/v1/push/devices/"+installation,Map.of("token",token,"platform","ANDROID"),200);
        when(pushGateway.enabled()).thenReturn(false);
        String disabled=send(a,b,key(),"Push disabled").at("/message/id").asText();
        assertThat(jdbc.queryForObject("select count(*) from push_deliveries where message_id=UUID_TO_BIN(?)",Integer.class,disabled)).isZero();
        when(pushGateway.enabled()).thenReturn(true);
        String queued=send(b,a,key(),"Before account switch").at("/message/id").asText();
        request(b,"PUT","/api/v1/push/devices/"+installation,Map.of("token",token,"platform","ANDROID"),200);
        request(a,"DELETE","/api/v1/push/devices/"+installation,null,200);
        assertThat(jdbc.queryForObject("select BIN_TO_UUID(user_id) from push_devices where installation_id=UUID_TO_BIN(?)",String.class,installation)).isEqualTo(b.id());
        for(String id:jdbc.queryForList("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,queued)) push.dispatch(UUID.fromString(id));
        verify(pushGateway,never()).send(any(),any(),any(),any(),any());
        // One token cannot remain registered to two installations/accounts.
        request(a,"PUT","/api/v1/push/devices/"+key(),Map.of("token",token,"platform","ANDROID"),200);
        assertThat(jdbc.queryForObject("select count(*) from push_devices where token=?",Integer.class,token)).isEqualTo(1);
        request(a,"PUT","/api/v1/push/devices/"+key(),Map.of("token","","platform","ANDROID"),400);
        request(a,"PUT","/api/v1/push/devices/"+key(),Map.of("token",token,"platform","WEB"),400);
    }

    @Test void pushRetriesFailuresAndRechecksMuteBeforeDispatch() throws Exception {
        var a=account();var b=account();
        request(b,"PUT","/api/v1/push/devices/"+key(),Map.of("token","retry-"+key(),"platform","ANDROID"),200);
        when(pushGateway.enabled()).thenReturn(true);
        var sent=send(a,b,key(),"Retry test");String message=sent.at("/message/id").asText(),room=sent.at("/room/id").asText();
        String job=jdbc.queryForObject("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,message);
        doThrow(new java.io.IOException("sensitive-provider-details")).when(pushGateway).send(any(),any(),any(),any(),any());
        push.dispatch(UUID.fromString(job));
        assertThat(jdbc.queryForObject("select attempts from push_deliveries where id=UUID_TO_BIN(?)",Integer.class,job)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select finished_at is null from push_deliveries where id=UUID_TO_BIN(?)",Boolean.class,job)).isTrue();
        push.dispatch(UUID.fromString(job)); // Backoff prevents immediate resend.
        verify(pushGateway,times(1)).send(any(),any(),any(),any(),any());
        doNothing().when(pushGateway).send(any(),any(),any(),any(),any());
        jdbc.update("update push_deliveries set next_attempt_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND where id=UUID_TO_BIN(?)",job);
        push.dispatch(UUID.fromString(job));push.dispatch(UUID.fromString(job));
        verify(pushGateway,times(2)).send(any(),any(),any(),any(),any());
        String muted=send(a,b,key(),"Mute after enqueue").at("/message/id").asText();
        request(b,"PUT","/api/v1/chat/rooms/"+room+"/notifications",Map.of("muted",true),200);
        for(String id:jdbc.queryForList("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,muted)) push.dispatch(UUID.fromString(id));
        verify(pushGateway,never()).send(any(),any(),eq(UUID.fromString(muted)),any(),any());
    }

    @Test void groupPushTargetsOnlyActiveVisibleRecipients() throws Exception {
        var owner=account();var member=account();String room=managedGroup(owner,member);
        request(owner,"PUT","/api/v1/push/devices/"+key(),Map.of("token","group-owner-token","platform","ANDROID"),200);
        request(member,"PUT","/api/v1/push/devices/"+key(),Map.of("token","group-member-token","platform","IOS"),200);
        when(pushGateway.enabled()).thenReturn(true);
        String visible=data(request(owner,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","Visible group message"),200)).at("/message/id").asText();
        for(String id:jdbc.queryForList("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,visible)) push.dispatch(UUID.fromString(id));
        verify(pushGateway).send(any(),eq(UUID.fromString(room)),eq(UUID.fromString(visible)),eq(UUID.fromString(member.id())),any());
        String message=data(request(owner,"POST","/api/v1/chat/rooms/"+room+"/messages",Map.of("clientMessageId",key(),"content","Group message"),200)).at("/message/id").asText();
        request(owner,"DELETE","/api/v1/chat/groups/"+room+"/members/"+member.id(),null,200);
        for(String id:jdbc.queryForList("select BIN_TO_UUID(id) from push_deliveries where message_id=UUID_TO_BIN(?)",String.class,message)) push.dispatch(UUID.fromString(id));
        verify(pushGateway,never()).send(any(),any(),eq(UUID.fromString(message)),any(),any());
    }
}
