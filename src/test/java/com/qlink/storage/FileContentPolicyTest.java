package com.qlink.storage;
import com.qlink.features.storage.service.FileContentPolicy;
import com.qlink.features.storage.enums.FileCategory;
import com.qlink.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class FileContentPolicyTest {
    final FileContentPolicy policy=new FileContentPolicy();
    static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6V7sAAAAASUVORK5CYII=");
    @Test void detectsActualBytesAndCategory() throws Exception {
        assertThat(policy.detect(new ByteArrayInputStream(PNG),"photo.png",FileCategory.IMAGE)).isEqualTo("image/png");
        assertThat(policy.detect(new ByteArrayInputStream("Hello, storage!".getBytes()),"notes.txt",null)).isEqualTo("text/plain");
    }
    @Test void dangerousContentAndMismatchedExtensionsAreRejected() {
        for(String data:List.of("<html><script>alert(1)</script></html>","#!/bin/sh\necho unsafe","<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")) {
            assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream(data.getBytes()),"photo.png",null)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream(PNG),"program.exe",null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream(PNG),"photo.jpg",null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream(new byte[]{'M','Z',0,0,1,2}),"photo.png",null)).isInstanceOf(ApiException.class);
    }
    @Test void categoryIsNotTrusted() {
        assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream(PNG),"photo.png",FileCategory.AUDIO))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_FILE");
    }
    @Test void filenameRemovesPathsControlsAndHeaderCharacters() {
        assertThat(policy.filename("C:\\fakepath\\../hello\r\n\".png")).isEqualTo("hello.png");
        assertThat(policy.filename("../../photo.png")).isEqualTo("photo.png");
        assertThat(policy.filename("a\u202E.png")).isEqualTo("a.png");
        assertThatThrownBy(() -> policy.filename("...")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.filename("a".repeat(256))).isInstanceOf(ApiException.class);
    }
    @Test void supportsMediaContainersAndStructuredJsonFromContent() throws Exception {
        var samples=new LinkedHashMap<String,byte[]>();
        samples.put("photo.gif","GIF89a".getBytes());
        samples.put("clip.mp3",new byte[]{'I','D','3',4,0,0,0,0,0,0});
        samples.put("sound.flac","fLaC".getBytes());
        samples.put("sound.wav",java.util.HexFormat.of().parseHex("524946462800000057415645666d74201000000001000100401f0000803e000002001000646174610400000000000000"));
        samples.put("voice.m4a",java.util.HexFormat.of().parseHex("00000018667479704d3441200000000069736f6d6d703432"));
        samples.put("video.mp4",java.util.HexFormat.of().parseHex("000000186674797069736f6d0000000069736f6d6d703432"));
        samples.put("video.webm",java.util.HexFormat.of().parseHex("1a45dfa3874282847765626d"));
        samples.put("voice.opus",java.util.HexFormat.of().parseHex("4f6767530002000000000000000000000000000000000000000001134f707573486561640101000080bb0000000000"));
        samples.put("paper.pdf","%PDF-1.4".getBytes());
        samples.put("data.json","{\"message\":\"hello\",\"values\":[1,true]}".getBytes());
        for(var sample:samples.entrySet()) {
            assertThatCode(() -> policy.detect(new ByteArrayInputStream(sample.getValue()),sample.getKey(),null)).as(sample.getKey()).doesNotThrowAnyException();
        }
    }
    @Test void rejectsMalformedJsonGenericOggAndMatroska() {
        for(String json:List.of("{bad}","{} {}","alert(1)","[1,]")) {
            assertThatThrownBy(()->policy.detect(new ByteArrayInputStream(json.getBytes()),"data.json",null)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(()->policy.detect(new ByteArrayInputStream("OggSinvalid".getBytes()),"voice.ogg",null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->policy.detect(new ByteArrayInputStream(java.util.HexFormat.of().parseHex("1a45dfa38b4282886d6174726f736b61")),"video.webm",null)).isInstanceOf(ApiException.class);
    }

    @Test void officeContainersAreDetectedFromContentsAndExtensionsMustMatch() throws Exception {
        for(var entry:Map.of("doc","WordDocument","xls","Workbook","ppt","PowerPoint Document").entrySet()) {
            var output=new ByteArrayOutputStream();
            try(var ole=new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
                ole.createDocument(new ByteArrayInputStream(new byte[64]),entry.getValue()); ole.writeFilesystem(output);
            }
            assertThat(policy.detect(new ByteArrayInputStream(output.toByteArray()),"file."+entry.getKey(),FileCategory.DOCUMENT)).startsWith("application/");
            assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream(output.toByteArray()),"file.docx",null)).isInstanceOf(ApiException.class);
        }
        for(var entry:Map.of("docx","word/document.xml|wordprocessingml.document","xlsx","xl/workbook.xml|spreadsheetml.sheet","pptx","ppt/presentation.xml|presentationml.presentation").entrySet()) {
            String[] parts=entry.getValue().split("[|]");
            var output=new ByteArrayOutputStream();
            try(var zip=new java.util.zip.ZipOutputStream(output)) {
                var contents=Map.of("_rels/.rels","<Relationships/>",parts[0],"<document/>","[Content_Types].xml",
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/"+parts[0]+"\" ContentType=\"application/vnd.openxmlformats-officedocument."+parts[1]+".main+xml\"/></Types>");
                for(var part:contents.entrySet()) { zip.putNextEntry(new java.util.zip.ZipEntry(part.getKey())); zip.write(part.getValue().getBytes()); zip.closeEntry(); }
            }
            assertThat(policy.detect(new ByteArrayInputStream(output.toByteArray()),"file."+entry.getKey(),FileCategory.DOCUMENT)).endsWith(parts[1]);
        }
        assertThatThrownBy(() -> policy.detect(new ByteArrayInputStream("not Office".getBytes()),"fake.doc",null)).isInstanceOf(ApiException.class);
    }
}
