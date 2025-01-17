/*****************************************************************************
 * Copyright (C) PicoContainer Organization. All rights reserved.            *
 * ------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the BSD      *
 * style license a copy of which has been included with this distribution in *
 * the LICENSE.txt file.                                                     *
 *                                                                           *
 *****************************************************************************/
package org.picocontainer.script.xml;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.thoughtworks.xstream.security.NoTypePermission;
import com.thoughtworks.xstream.security.NullPermission;
import com.thoughtworks.xstream.security.PrimitiveTypePermission;
import com.thoughtworks.xstream.security.TypeHierarchyPermission;
import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.reflection.Sun14ReflectionProvider;

/**
 * @author Paul Hammant
 * @author Marcos Tarruella
 */
public class XStreamComponentInstanceFactoryTestCase {

    @Test public void testDeserializationWithDefaultMode() throws ParserConfigurationException, IOException, SAXException {
        runDeserializationTest(new XStreamComponentInstanceFactory());
    }

    @Test public void testDeserializationInEncancedMode() throws ParserConfigurationException, IOException, SAXException {
        XStream xs = new XStream(new Sun14ReflectionProvider());
        xs.addPermission(NoTypePermission.NONE); //forbid everything
        xs.addPermission(NullPermission.NULL);   // allow "null"
        xs.addPermission(PrimitiveTypePermission.PRIMITIVES); // allow primitive types
        xs.addPermission(new TypeHierarchyPermission(org.picocontainer.script.xml.TestBean.class)); // allow primitive types
        runDeserializationTest(new XStreamComponentInstanceFactory(xs));
    }

    @Test public void testDeserializationInPureJavaMode() throws ParserConfigurationException, IOException, SAXException {
        runDeserializationTest(new PureJavaXStreamComponentInstanceFactory(org.picocontainer.script.xml.TestBean.class));
    }

    public void runDeserializationTest(XMLComponentInstanceFactory factory) throws ParserConfigurationException, IOException, SAXException {
        StringReader sr = new StringReader("" +
                "<org.picocontainer.script.xml.TestBean>" +
                "<foo>10</foo>" +
                "<bar>hello</bar>" +
                "</org.picocontainer.script.xml.TestBean>");
        InputSource is = new InputSource(sr);
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.parse(is);

        Object o = factory.makeInstance(null, doc.getDocumentElement(), Thread.currentThread().getContextClassLoader());
        TestBean bean = (TestBean) o;
        assertEquals("hello", bean.getBar());
        assertEquals(10, bean.getFoo());
    }

}
