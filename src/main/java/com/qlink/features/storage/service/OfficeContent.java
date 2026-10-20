package com.qlink.features.storage.service;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Detect Office containers from their contents, not the supplied extension. */
final class OfficeContent {
    private OfficeContent() {}
    static String detect(InputStream input) throws IOException {
        var stream = new BufferedInputStream(input);
        stream.mark(8);
        byte[] magic = stream.readNBytes(8);
        stream.reset();
        try {
            if (Arrays.equals(magic, HexFormat.of().parseHex("d0cf11e0a1b11ae1"))) {
                try (var ole = new POIFSFileSystem(stream)) {
                    var root = ole.getRoot();
                    if (root.hasEntry("WordDocument")) return "application/msword";
                    if (root.hasEntry("Workbook") || root.hasEntry("Book")) return "application/vnd.ms-excel";
                    if (root.hasEntry("PowerPoint Document")) return "application/vnd.ms-powerpoint";
                }
            } else if (magic.length >= 4 && magic[0]=='P' && magic[1]=='K' && magic[2]==3 && magic[3]==4) {
                var names = new HashSet<String>();
                byte[] types = null;
                long expanded = 0;
                try (var zip = new ZipInputStream(stream)) {
                    for (var entry=zip.getNextEntry(); entry!=null; entry=zip.getNextEntry()) {
                        if (names.size()>=10000 || !names.add(entry.getName())) throw StorageErrors.unsupported();
                        var capture = entry.getName().equals("[Content_Types].xml") ? new ByteArrayOutputStream() : null;
                        byte[] buffer=new byte[8192];
                        for (int n; (n=zip.read(buffer))!=-1;) {
                            expanded+=n;
                            if (expanded>100L*1024*1024) throw StorageErrors.unsupported();
                            if (capture!=null) {
                                if(capture.size()+n>1024*1024) throw StorageErrors.unsupported();
                                capture.write(buffer,0,n);
                            }
                        }
                        if (capture!=null) types=capture.toByteArray();
                    }
                }
                if(types==null || !names.contains("_rels/.rels")) throw StorageErrors.unsupported();
                var factory=DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setNamespaceAware(true);
                var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(types));
                var overrides=document.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types","Override");
                var formats=Map.of("/word/document.xml", "wordprocessingml.document",
                    "/xl/workbook.xml", "spreadsheetml.sheet", "/ppt/presentation.xml", "presentationml.presentation");
                String result=null;
                for(int i=0;i<overrides.getLength();i++) {
                    var element=(org.w3c.dom.Element)overrides.item(i);
                    String part=element.getAttribute("PartName"), format=formats.get(part);
                    if(format!=null && names.contains(part.substring(1)) && element.getAttribute("ContentType").equals("application/vnd.openxmlformats-officedocument."+format+".main+xml")) {
                        if(result!=null) throw StorageErrors.unsupported();
                        result="application/vnd.openxmlformats-officedocument."+format;
                    }
                }
                if(result!=null) return result;
            }
        } catch (com.qlink.common.exception.ApiException ex) { throw ex; }
        catch (Exception ex) { throw StorageErrors.unsupported(); }
        throw StorageErrors.unsupported();
    }
}
