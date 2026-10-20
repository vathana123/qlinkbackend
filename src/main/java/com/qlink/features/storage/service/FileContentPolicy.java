package com.qlink.features.storage.service;

import com.qlink.features.storage.enums.FileCategory;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;

@Component
public class FileContentPolicy {
    private record Format(FileCategory category, Set<String> extensions) {}
    private static Format format(FileCategory category, String... extensions) { return new Format(category,Set.of(extensions)); }
    private static final Map<String,Format> ALLOWED = Map.ofEntries(
        Map.entry("image/jpeg",format(FileCategory.IMAGE,"jpg","jpeg")),
        Map.entry("image/png",format(FileCategory.IMAGE,"png")),
        Map.entry("image/gif",format(FileCategory.IMAGE,"gif")),
        Map.entry("image/webp",format(FileCategory.IMAGE,"webp")),
        Map.entry("audio/mpeg",format(FileCategory.AUDIO,"mp3")),
        Map.entry("audio/vnd.wave",format(FileCategory.AUDIO,"wav")),
        Map.entry("audio/x-wav",format(FileCategory.AUDIO,"wav")),
        Map.entry("audio/x-flac",format(FileCategory.AUDIO,"flac")),
        Map.entry("audio/flac",format(FileCategory.AUDIO,"flac")),
        Map.entry("audio/ogg",format(FileCategory.AUDIO,"ogg","opus")),
        Map.entry("application/ogg",format(FileCategory.AUDIO,"ogg","opus")),
        Map.entry("audio/mp4",format(FileCategory.AUDIO,"m4a")),
        Map.entry("video/mp4",format(FileCategory.VIDEO,"mp4")),
        Map.entry("video/quicktime",format(FileCategory.VIDEO,"mov")),
        Map.entry("video/webm",format(FileCategory.VIDEO,"webm")),
        Map.entry("application/msword",format(FileCategory.DOCUMENT,"doc")),
        Map.entry("application/vnd.ms-excel",format(FileCategory.DOCUMENT,"xls")),
        Map.entry("application/vnd.ms-powerpoint",format(FileCategory.DOCUMENT,"ppt")),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document",format(FileCategory.DOCUMENT,"docx")),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",format(FileCategory.DOCUMENT,"xlsx")),
        Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",format(FileCategory.DOCUMENT,"pptx")),
        Map.entry("application/pdf",format(FileCategory.DOCUMENT,"pdf")),
        Map.entry("text/plain",format(FileCategory.DOCUMENT,"txt","csv")),
        Map.entry("application/json",format(FileCategory.OTHER,"json"))
    );
    private final Tika tika = new Tika();
    private final JsonFactory json = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(100).maxStringLength(65536).maxNumberLength(1000).build()).build();
    public String detect(InputStream input, String name, FileCategory requested) throws IOException {
        // No filename or client MIME supplied to detection: they must not influence the result.
        String type = tika.detect(input);
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (Set.of("doc","xls","ppt","docx","xlsx","pptx").contains(extension)) {
            type = OfficeContent.detect(input);
        }
        if (extension.equals("json") && Set.of("text/plain","text/javascript","application/json").contains(type)) {
            validateJson(input);
            type = "application/json";
        } else if (type.equals("video/quicktime")) {
            byte[] header=input.readNBytes(12);
            if (header.length==12 && new String(header,4,4,StandardCharsets.US_ASCII).equals("ftyp")
                && Set.of("isom","iso2","iso6","avc1","dash","mp41","mp42").contains(new String(header,8,4,StandardCharsets.US_ASCII)))
                type="video/mp4";
        } else if (type.equals("application/x-matroska") && webm(input)) {
            type = "video/webm";
        } else if (type.equals("application/ogg")) {
            if (!audioOgg(input)) throw StorageErrors.unsupported();
            type = "audio/ogg";
        }
        var format = ALLOWED.get(type);
        if (format == null || !format.extensions().contains(extension)) throw StorageErrors.unsupported();
        if (requested != null && requested != format.category()) throw StorageErrors.invalid("Category does not match detected content");
        return type;
    }
    private void validateJson(InputStream input) {
        try (var parser=json.createParser(ObjectReadContext.empty(), input)) {
            JsonToken token=parser.nextToken();
            if (token!=JsonToken.START_OBJECT && token!=JsonToken.START_ARRAY) throw StorageErrors.unsupported();
            int depth=1;
            while (depth>0) {
                token=parser.nextToken();
                if (token==null) throw StorageErrors.unsupported();
                if (token==JsonToken.START_OBJECT || token==JsonToken.START_ARRAY) depth++;
                if (token==JsonToken.END_OBJECT || token==JsonToken.END_ARRAY) depth--;
                // Force string validation without building a document tree.
                if (token==JsonToken.VALUE_STRING || token==JsonToken.PROPERTY_NAME) parser.getString();
            }
            if (parser.nextToken()!=null) throw StorageErrors.unsupported();
        } catch (tools.jackson.core.JacksonException ex) { throw StorageErrors.unsupported(); }
    }
    private boolean audioOgg(InputStream input) throws IOException {
        byte[] header=input.readNBytes(512);
        if (header.length<28 || header[4]!=0 || (header[5]&2)==0) return false;
        int packet=27+Byte.toUnsignedInt(header[26]);
        if (packet+8>header.length) return false;
        String codec=new String(header,packet,8,StandardCharsets.ISO_8859_1);
        return codec.equals("OpusHead") || codec.startsWith(String.valueOf((char)1)+"vorbis");
    }
    private boolean webm(InputStream input) throws IOException {
        byte[] header=input.readNBytes(4096);
        if (header.length<8) return false;
        int[] offset={4}; long length=ebmlInteger(header,offset,false);
        if (length<0 || length>header.length-offset[0]) return false;
        int end=offset[0]+(int)length;
        while (offset[0]<end) {
            long id=ebmlInteger(header,offset,true), size=ebmlInteger(header,offset,false);
            if (size<0 || size>end-offset[0]) return false;
            if (id==0x4282) return size==4 && new String(header,offset[0],4,StandardCharsets.US_ASCII).equals("webm");
            offset[0]+=(int)size;
        }
        return false;
    }
    private long ebmlInteger(byte[] bytes,int[] offset,boolean retainMarker) {
        if (offset[0]>=bytes.length) return -1;
        int first=Byte.toUnsignedInt(bytes[offset[0]++]), mask=128, count=1;
        while (mask>0 && (first&mask)==0) { mask>>=1; count++; }
        if (mask==0 || count>4 || offset[0]+count-1>bytes.length) return -1;
        long value=retainMarker?first:first&(mask-1);
        for(int i=1;i<count;i++) value=(value<<8)|Byte.toUnsignedInt(bytes[offset[0]++]);
        return value;
    }
    public Map<String, String> extensions() {
        var result = new TreeMap<String,String>();
        ALLOWED.forEach((mime, format) -> format.extensions().forEach(ext -> result.put(ext,mime)));
        return result;
    }
    public FileCategory category(String type) { return ALLOWED.get(type).category(); }
    public String filename(String original) {
        String name = Normalizer.normalize(original == null ? "" : original, Normalizer.Form.NFKC);
        name = name.replace('\\','/');
        name = name.substring(name.lastIndexOf('/') + 1);
        StringBuilder safe = new StringBuilder();
        name.codePoints().forEach(cp -> {
            int kind = Character.getType(cp);
            if (!Character.isISOControl(cp) && kind != Character.FORMAT && kind != Character.SURROGATE
                && "<>:\"|?*".indexOf(cp) < 0) safe.appendCodePoint(cp);
        });
        name = safe.toString().strip();
        while (name.startsWith(".")) name = name.substring(1);
        while (name.endsWith(".")) name = name.substring(0,name.length()-1).stripTrailing();
        if (name.isBlank() || name.length() > 255) throw StorageErrors.invalid("Filename must contain 1 to 255 characters");
        return name;
    }
}
