package com.asterion.nexus

import org.junit.Test
import org.junit.Assert.*
import javax.xml.parsers.DocumentBuilderFactory

class NexusDocxEditionTest{
    private val ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun xml(body:String)="<w:document xmlns:w='$ns' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><w:body>"+body+"</w:body></w:document>"
    @Test fun sameStyleRunsGroupWithoutChangingStructure(){
        val input=xml("<w:p><w:r><w:t>日本</w:t></w:r><w:r><w:t>語</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>本</w:t></w:r><w:hyperlink r:id='rId7'><w:r><w:t>雨</w:t></w:r></w:hyperlink></w:p>")
        val seen=mutableListOf<String>()
        val out=NexusDocxEdition.rewrite(input.toByteArray(),{false}){seen.add(it);"English"}
        assertEquals(listOf("日本語","本","雨"),seen)
        val f=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}
        val doc=f.newDocumentBuilder().parse(out.inputStream())
        assertEquals(4,doc.getElementsByTagNameNS(ns,"t").length)
        assertEquals("English",doc.getElementsByTagNameNS(ns,"t").item(0).textContent)
        assertEquals("",doc.getElementsByTagNameNS(ns,"t").item(1).textContent)
        assertEquals(1,doc.getElementsByTagNameNS(ns,"b").length)
        val link=doc.getElementsByTagNameNS(ns,"hyperlink").item(0) as org.w3c.dom.Element
        assertEquals("rId7",link.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"))
    }
    @Test fun fieldResultsNotRewritten(){
        val input=xml("<w:p><w:r><w:fldChar w:fldCharType='begin'/></w:r><w:r><w:instrText>PAGE</w:instrText></w:r><w:r><w:t>日本語</w:t></w:r><w:r><w:fldChar w:fldCharType='end'/></w:r></w:p>")
        val result=NexusDocxEdition.rewrite(input.toByteArray(),{false}){error("Field changed")}
        assertTrue(String(result).contains("日本語"))
    }
    @Test fun unsafeDtdRejected(){
        try{
            NexusDocxEdition.rewrite(("<!DOCTYPE w:document [<!ENTITY x 'unsafe'>]>"+xml("<w:p/>")).toByteArray(),{false}){it}
            fail("DTD accepted")
        }catch(_:IllegalArgumentException){}
    }
}
