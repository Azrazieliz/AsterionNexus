package com.asterion.nexus;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Read-only spreadsheet adapter. Actual cells, coordinates and workbook
 * relationship order are preserved. No macros, formulas or external links run. */
public final class NexusSpreadsheet {
    public static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_XML=16*1024*1024, MAX_TOTAL=64*1024*1024, MAX_CELLS=200000;
    private static final int ROW_BLOCK=48,COL_BLOCK=24;
    private static final String REL_NS="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final class Cell{
        final String text,formula;
        Cell(String text,String formula){this.text=text;this.formula=formula;}
    }
    private static final class Sheet{
        final String name;
        final TreeMap<Integer,TreeMap<Integer,Cell>> rows=new TreeMap<>();
        Sheet(String n){name=n;}
    }
    private static final class Page{
        final Sheet sheet;final int firstRow,lastRow,firstCol,lastCol;
        Page(Sheet s,int a,int b,int c,int d){sheet=s;firstRow=a;lastRow=b;firstCol=c;lastCol=d;}
    }
    private final List<Sheet> sheets=new ArrayList<>();
    private final List<Page> pages=new ArrayList<>();
    private int cells=0;
    private NexusSpreadsheet(){}
    public static boolean supported(String mime,String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return XLSX.equals(mime)||"text/csv".equals(mime)||n.endsWith(".xlsx")||n.endsWith(".csv");
    }
    public static NexusSpreadsheet open(byte[] data,boolean csv)throws Exception{
        if(data==null||data.length==0||data.length>24*1024*1024)throw new IOException("Spreadsheet size limit");
        NexusSpreadsheet result=new NexusSpreadsheet();
        if(csv)result.parseCsv(data);else result.parseWorkbook(data);
        result.paginate();
        if(result.pages.isEmpty())throw new IOException("No supported worksheet");
        return result;
    }
    private static Map<String,byte[]> unzip(byte[] data)throws IOException{
        Map<String,byte[]> parts=new HashMap<>();
        Set<String> seen=new HashSet<>();
        long expanded=0;int count=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))){
            for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                if(++count>10000)throw new IOException("Too many workbook parts");
                String name=e.getName();
                if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains("\0"))throw new IOException("Unsafe workbook part");
                for(String part:name.split("/"))if(part.equals("..")||part.equals("."))throw new IOException("Unsafe workbook part");
                if(!seen.add(name.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate workbook part");
                boolean useful=name.equals("xl/workbook.xml")||name.equals("xl/_rels/workbook.xml.rels")
                    ||name.equals("xl/sharedStrings.xml")||name.startsWith("xl/worksheets/")&&name.endsWith(".xml");
                ByteArrayOutputStream out=useful&&!e.isDirectory()?new ByteArrayOutputStream():null;
                int total=0;byte[] buf=new byte[32768];
                for(int n;(n=zip.read(buf))!=-1;){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Spreadsheet cancelled");
                    if(n==0)continue;
                    expanded+=n;total+=n;
                    if(expanded>MAX_TOTAL||total>MAX_XML)throw new IOException("Workbook expansion limit");
                    if(out!=null)out.write(buf,0,n);
                }
                if(out!=null)parts.put(name,out.toByteArray());
                zip.closeEntry();
            }
        }
        return parts;
    }
    private static void parse(byte[] raw,DefaultHandler handler)throws Exception{
        if(raw==null)throw new IOException("Missing workbook XML");
        SAXParserFactory factory=SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        XMLReader reader=factory.newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId,systemId)->{throw new SAXException("External entity prohibited");});
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        // OOXML parts are UTF-8/UTF-16 XML. Refuse all DTD declarations,
        // including internal entity expansion, before invoking a parser.
        String probe;
        if(raw.length>=2&&((raw[0]&255)==255&&(raw[1]&255)==254||(raw[0]&255)==254&&(raw[1]&255)==255))
            probe=new String(raw,StandardCharsets.UTF_16);
        else if(raw.length>=2&&raw[0]==0&&raw[1]=='<')probe=new String(raw,StandardCharsets.UTF_16BE);
        else if(raw.length>=2&&raw[0]=='<'&&raw[1]==0)probe=new String(raw,StandardCharsets.UTF_16LE);
        else probe=new String(raw,StandardCharsets.UTF_8);
        if(probe.indexOf('\0')>=0||probe.contains("<!DOCTYPE")||probe.contains("<!ENTITY"))
            throw new IOException("Workbook DTD or unsupported XML encoding");
        reader.parse(new InputSource(new StringReader(probe.startsWith("\ufeff")?probe.substring(1):probe)));
    }
    private static String tag(String local,String qualified){return local.isEmpty()?qualified:local;}
    private void parseWorkbook(byte[] raw)throws Exception{
        Map<String,byte[]> parts=unzip(raw);
        Map<String,String> relationships=new HashMap<>();
        parse(parts.get("xl/_rels/workbook.xml.rels"),new DefaultHandler(){
            @Override public void startElement(String uri,String local,String q,Attributes a)throws SAXException{
                if(!"Relationship".equals(tag(local,q)))return;
                String type=a.getValue("Type");
                if(type==null||!type.endsWith("/worksheet"))return;
                if("External".equalsIgnoreCase(a.getValue("TargetMode")))throw new SAXException("External worksheet");
                String id=a.getValue("Id"),target=a.getValue("Target");
                if(id==null||target==null)throw new SAXException("Invalid worksheet relationship");
                if(target.startsWith("/"))target=target.substring(1);else target="xl/"+target;
                if(!target.startsWith("xl/worksheets/")||!target.endsWith(".xml")||target.contains("..")||target.contains("\\")||target.contains("%")||target.contains("?")||target.contains("#"))
                    throw new SAXException("Unsupported worksheet target");
                if(relationships.put(id,target)!=null)throw new SAXException("Duplicate worksheet relationship");
            }
        });
        List<String[]> ordered=new ArrayList<>();
        parse(parts.get("xl/workbook.xml"),new DefaultHandler(){
            @Override public void startElement(String uri,String local,String q,Attributes a)throws SAXException{
                if(!"sheet".equals(tag(local,q)))return;
                if(ordered.size()>=256)throw new SAXException("Worksheet count limit");
                String id=a.getValue(REL_NS,"id");
                if(id==null)id=a.getValue("r:id");
                String name=a.getValue("name");
                if(name==null||id==null||!relationships.containsKey(id))throw new SAXException("Unresolved worksheet");
                String state=a.getValue("state");
                ordered.add(new String[]{name+("hidden".equals(state)||"veryHidden".equals(state)?" (hidden)":""),relationships.get(id)});
            }
        });
        List<String> shared=new ArrayList<>();
        byte[] strings=parts.get("xl/sharedStrings.xml");
        if(strings!=null)parse(strings,new DefaultHandler(){
            StringBuilder value;boolean inText;int phonetic;
            @Override public void startElement(String uri,String local,String q,Attributes a)throws SAXException{
                String t=tag(local,q);
                if("si".equals(t)){value=new StringBuilder();phonetic=0;}
                else if("rPh".equals(t))phonetic++;
                else if("t".equals(t)&&value!=null&&phonetic==0)inText=true;
            }
            @Override public void characters(char[] ch,int start,int length)throws SAXException{
                if(inText&&value!=null){if(value.length()+length>32768)throw new SAXException("Cell text limit");value.append(ch,start,length);}
            }
            @Override public void endElement(String uri,String local,String q)throws SAXException{
                String t=tag(local,q);
                if("t".equals(t))inText=false;
                else if("rPh".equals(t))phonetic--;
                else if("si".equals(t)){
                    if(shared.size()>=MAX_CELLS)throw new SAXException("Shared string limit");
                    shared.add(value.toString());value=null;
                }
            }
        });
        Set<String> used=new HashSet<>();
        for(String[] entry:ordered){
            if(!used.add(entry[1]))throw new IOException("Repeated worksheet target");
            Sheet sheet=new Sheet(entry[0]);sheets.add(sheet);
            parse(parts.get(entry[1]),new DefaultHandler(){
                int row=0,col=0;String type,formula;StringBuilder val;
                boolean inCell,inValue,inFormula,inText;int phonetic;
                @Override public void startElement(String uri,String local,String q,Attributes a)throws SAXException{
                    String t=tag(local,q);
                    if("row".equals(t)){
                        row=number(a.getValue("r"),row+1,1048576);col=0;
                    }else if("c".equals(t)){
                        if(inCell||row<1)throw new SAXException("Invalid cell nesting");
                        String ref=a.getValue("r");
                        if(ref!=null){
                            int[] pos=coordinate(ref);
                            if(pos[0]!=row)throw new SAXException("Cell row mismatch");
                            col=pos[1];
                        }else col++;
                        if(col>16384)throw new SAXException("Column limit");
                        type=a.getValue("t");formula="";val=new StringBuilder();inCell=true;phonetic=0;
                    }else if(inCell){
                        if("v".equals(t))inValue=true;
                        else if("f".equals(t))inFormula=true;
                        else if("rPh".equals(t))phonetic++;
                        else if("t".equals(t)&&"inlineStr".equals(type)&&phonetic==0)inText=true;
                    }
                }
                @Override public void characters(char[] ch,int off,int len)throws SAXException{
                    if(!inCell)return;
                    if(inFormula){if(formula.length()+len>8192)throw new SAXException("Formula length limit");formula+=new String(ch,off,len);}
                    else if(inValue||inText){if(val.length()+len>32768)throw new SAXException("Cell text limit");val.append(ch,off,len);}
                }
                @Override public void endElement(String uri,String local,String q)throws SAXException{
                    String t=tag(local,q);
                    if("v".equals(t))inValue=false;
                    else if("f".equals(t))inFormula=false;
                    else if("t".equals(t))inText=false;
                    else if("rPh".equals(t)&&inCell)phonetic--;
                    else if("c".equals(t)){
                        String text=val.toString();
                        if("s".equals(type)){
                            int idx=number(text,-1,Integer.MAX_VALUE);
                            if(idx<0||idx>=shared.size())throw new SAXException("Invalid shared string index");
                            text=shared.get(idx);
                        }else if("b".equals(type)){if(!text.equals("0")&&!text.equals("1"))throw new SAXException("Invalid boolean cell");text=text.equals("1")?"TRUE":"FALSE";}
                        if(!text.isEmpty()||!formula.isEmpty())put(sheet,row,col,text,formula);
                        inCell=false;inValue=false;inFormula=false;inText=false;
                    }
                }
            });
        }
    }
    private static int number(String value,int fallback,int max)throws SAXException{
        if(value==null)return fallback;
        try{int n=Integer.parseInt(value);if(n<0||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException e){throw new SAXException("Invalid workbook index");}
    }
    private static int[] coordinate(String raw)throws SAXException{
        if(!raw.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw new SAXException("Invalid cell reference");
        int i=0,col=0;while(i<raw.length()&&raw.charAt(i)>='A'&&raw.charAt(i)<='Z'){col=col*26+raw.charAt(i++)-'A'+1;}
        int row=number(raw.substring(i),-1,1048576);
        if(col>16384||row<1)throw new SAXException("Cell outside XLSX limits");
        return new int[]{row,col};
    }
    private void put(Sheet sheet,int row,int col,String text,String formula)throws SAXException{
        if(++cells>MAX_CELLS||row<1||col<1||row>1048576||col>16384)throw new SAXException("Workbook cell limit");
        if(sheet.rows.computeIfAbsent(row,k->new TreeMap<>()).put(col,new Cell(text,formula))!=null)
            throw new SAXException("Duplicate cell coordinate");
    }
    private void parseCsv(byte[] raw)throws Exception{
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        if(text.startsWith("\ufeff"))text=text.substring(1);
        Sheet sheet=new Sheet("CSV");sheets.add(sheet);
        int row=1,col=1;boolean quoted=false,closed=false,started=false;
        StringBuilder field=new StringBuilder();
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){
                if(c=='"'){
                    if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}
                    else{quoted=false;closed=true;}
                }else field.append(c);
            }else if(c==','||c=='\r'||c=='\n'){
                put(sheet,row,col++,field.toString(),"");field.setLength(0);started=false;closed=false;
                if(c!=','){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row++;col=1;}
            }else if(c=='"'&&!started&&!closed){quoted=true;started=true;}
            else{if(closed||c=='"')throw new IOException("Malformed CSV quoting");field.append(c);started=true;}
            if(field.length()>32768)throw new IOException("CSV field limit");
        }
        if(quoted)throw new IOException("Unterminated quoted CSV field");
        if(started||closed||col>1)put(sheet,row,col,field.toString(),"");
    }
    private void paginate()throws IOException{
        for(Sheet sheet:sheets){
            TreeMap<Integer,TreeSet<Integer>> blocks=new TreeMap<>();
            for(Map.Entry<Integer,TreeMap<Integer,Cell>> row:sheet.rows.entrySet()){
                int rb=(row.getKey()-1)/ROW_BLOCK;
                TreeSet<Integer> columns=blocks.computeIfAbsent(rb,k->new TreeSet<>());
                for(int col:row.getValue().keySet())columns.add((col-1)/COL_BLOCK);
            }
            if(blocks.isEmpty()){pages.add(new Page(sheet,1,1,1,1));continue;}
            for(Map.Entry<Integer,TreeSet<Integer>> block:blocks.entrySet()){
                for(int c:block.getValue()){
                    if(pages.size()>=12000)throw new IOException("Spreadsheet page limit");
                    int r=block.getKey()*ROW_BLOCK+1, first=c*COL_BLOCK+1;
                    pages.add(new Page(sheet,r,Math.min(1048576,r+ROW_BLOCK-1),first,Math.min(16384,first+COL_BLOCK-1)));
                }
            }
        }
    }
    public int count(){return pages.size();}
    public String label(int index){
        Page p=pages.get(index);return p.sheet.name+" · "+column(p.firstCol)+p.firstRow+":"+column(p.lastCol)+p.lastRow;
    }
    private static String column(int n){StringBuilder s=new StringBuilder();while(n>0){n--;s.append((char)('A'+n%26));n/=26;}return s.reverse().toString();}
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    public String html(int index){
        Page p=pages.get(index);
        StringBuilder h=new StringBuilder("<html><meta name='viewport' content='width=device-width, initial-scale=1'><style>body{background:#16191e;color:#f2f4f6;font:14px sans-serif;margin:8px}table{border-collapse:collapse}th,td{border:1px solid #444b56;padding:7px;min-width:76px;max-width:320px;white-space:pre-wrap;overflow-wrap:anywhere;vertical-align:top}th{background:#282d35;color:#cdd3dd;min-width:34px}small{display:block;color:#9da8b8;margin-top:3px}</style><h3>");
        h.append(esc(label(index))).append("</h3><p>Read-only · stored values; formulas are not executed. Number/date formatting may be raw.</p><table><tr><th></th>");
        for(int c=p.firstCol;c<=p.lastCol;c++)h.append("<th>").append(column(c)).append("</th>");
        h.append("</tr>");
        for(int r=p.firstRow;r<=p.lastRow;r++){
            Map<Integer,Cell> row=p.sheet.rows.get(r);
            h.append("<tr><th>").append(r).append("</th>");
            for(int c=p.firstCol;c<=p.lastCol;c++){
                Cell cell=row==null?null:row.get(c);h.append("<td>");
                if(cell!=null){h.append(esc(cell.text));if(!cell.formula.isEmpty())h.append("<small>Formula: =").append(esc(cell.formula)).append("</small>");}
                h.append("</td>");
            }
            h.append("</tr>");
        }
        return h.append("</table></html>").toString();
    }
}
