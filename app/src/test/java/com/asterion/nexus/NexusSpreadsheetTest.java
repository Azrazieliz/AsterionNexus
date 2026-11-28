package com.asterion.nexus;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

public class NexusSpreadsheetTest {
    private static byte[] book(String sheet,String target,String strings)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            put(zip,"xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Real sheet' r:id='rId7'/></sheets></workbook>");
            put(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId7' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='"+target+"'/></Relationships>");
            put(zip,"xl/worksheets/unusual.xml",sheet);
            if(strings!=null)put(zip,"xl/sharedStrings.xml",strings);
        }
        return bytes.toByteArray();
    }
    private static void put(ZipOutputStream zip,String path,String text)throws IOException{
        zip.putNextEntry(new ZipEntry(path));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    @Test public void actualWorkbookRelationshipsAndCells()throws Exception{
        NexusSpreadsheet sheet=NexusSpreadsheet.open(book("<worksheet><sheetData><row r='2'><c r='A2' t='s'><v>0</v></c><c r='C2'><f>1+2</f><v>3</v></c><c r='D2' t='b'><v>1</v></c></row><row r='100'><c r='AA100' t='inlineStr'><is><t>日本語</t></is></c></row></sheetData></worksheet>","worksheets/unusual.xml","<sst><si><r><t>First &amp; </t></r><r><t>&lt;last&gt;</t></r></si></sst>"),false);
        assertEquals(2,sheet.count());
        assertTrue(sheet.label(0).contains("Real sheet"));
        assertTrue(sheet.html(0).contains("First &amp; &lt;last&gt;"));
        assertTrue(sheet.html(0).contains("Formula: =1+2"));
        assertTrue(sheet.html(0).contains("TRUE"));
        assertTrue(sheet.label(1).contains("Y97"));
        assertTrue(sheet.html(1).contains("日本語"));
    }
    @Test public void csvQuotedMultilineAndEscapedCells()throws Exception{
        NexusSpreadsheet sheet=NexusSpreadsheet.open("Name,Value\r\n\"A, B\",\"Line 1\nLine 2\"\r\n\"a\"\"b\",<script>\r\n".getBytes(StandardCharsets.UTF_8),true);
        assertTrue(sheet.html(0).contains("A, B"));
        assertTrue(sheet.html(0).contains("Line 1\nLine 2"));
        assertTrue(sheet.html(0).contains("a&quot;b"));
        assertTrue(sheet.html(0).contains("&lt;script&gt;"));
    }
    @Test public void rejectExternalOrTraversalRelationship()throws Exception{
        for(String target:new String[]{"../outside.xml","https://example.invalid/sheet.xml"}){
            try{NexusSpreadsheet.open(book("<worksheet/>",target,null),false);fail("Admitted external or traversing target");}
            catch(Exception expected){}
        }
    }
    @Test public void refuseDtdAndDuplicateCoordinates()throws Exception{
        for(String xml:new String[]{"<!DOCTYPE worksheet [<!ENTITY x 'unsafe'>]><worksheet/>","<worksheet><sheetData><row r='1'><c r='A1'><v>1</v></c><c r='A1'><v>2</v></c></row></sheetData></worksheet>"}){
            try{NexusSpreadsheet.open(book(xml,"worksheets/unusual.xml",null),false);fail("Admitted unsafe workbook");}
            catch(Exception expected){}
        }
    }
    @Test public void refuseInvalidCsvQuoting()throws Exception{
        try{NexusSpreadsheet.open("\"unterminated".getBytes(StandardCharsets.UTF_8),true);fail("Accepted incomplete CSV");}
        catch(IOException expected){}
    }
}
