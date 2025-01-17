/*******************************************************************************
 * Copyright (c) PicoContainer Organization. All rights reserved.
 * ---------------------------------------------------------------------------
 * The software in this package is published under the terms of the BSD style
 * license a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 ******************************************************************************/
package org.picocontainer.web.remoting;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.Arrays;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.HierarchicalStreamDriver;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.WriterWrapper;

/**
 * Servlet that uses Ruby as the form of the reply.
 *
 * @author Jean Lazarou
 * @author Paul Hammant
 */
@SuppressWarnings("serial")
public class RubyPicoWebRemotingServlet extends AbstractPicoWebRemotingServlet  {


    @Override
	protected XStream createXStream() {
		return new XStream(makeRubyDriver());
	}

    public static HierarchicalStreamDriver makeRubyDriver() {
        HierarchicalStreamDriver driver = new HierarchicalStreamDriver() {
            public HierarchicalStreamReader createReader(Reader reader) {
                throw new UnsupportedOperationException();
            }

            public HierarchicalStreamReader createReader(InputStream inputStream) {
                throw new UnsupportedOperationException();
            }

            public HierarchicalStreamReader createReader(URL url) {
                return null; //TODO-2025
            }

            public HierarchicalStreamReader createReader(File file) {
                return null; //TODO-2025
            }

            public HierarchicalStreamWriter createWriter(Writer out) {
                HierarchicalStreamWriter rubyWriter = new RubyWriter(out);
                return new WriterWrapper(rubyWriter) {
                    public void startNode(String name) {
                        startNode(name, null);
                    }

                    @SuppressWarnings("unchecked")
					public void startNode(String name, Class clazz) {
                        ((RubyWriter) wrapped).startNode(name.replace('-', '_'), clazz);
                    }
                };
            }

            public HierarchicalStreamWriter createWriter(OutputStream outputStream) {
                throw new UnsupportedOperationException();
            }
        };
        return driver;
    }

    /**
     * Write the response as a Ruby hash, also handle classdef requests
     */
    protected void respond(HttpServletRequest req, HttpServletResponse resp, String pathInfo) throws IOException {
        if ("/classdefs".equals(pathInfo)) {

            final String function = req.getParameter("fn") != null ? req.getParameter("fn") : "connection.submit";

            final String init = req.getParameter("init") != null ? req.getParameter("init") : "connection";

            String classList = req.getQueryString();
            if (classList.contains("&")) {
                classList = classList.substring(0, classList.indexOf("&"));
            }
            final String[] classes = classList.split(",");

            resp.setContentType("text/plain");
            final ServletOutputStream outputStream = resp.getOutputStream();
            MethodVisitor mapv = new MethodVisitor() {

                public void method(String methodName, Method method) throws IOException {
                    outputStream.print("\n\n  def " + methodName);
                    Parameter[] params = method.getParameters();
                    for (int i = 0; i < params.length; i++) {
                        String name = params[i].getName();
                        Class<?> pType = params[i].getType();
                        if (!isExcludedFromClassDefPublication(pType, name)) {
                            outputStream.print((i > 0 ? ", " : " ") + name);
                        }
                    }
                    outputStream.print("\n");
                    String rubyFunctionName = function;
                    if (method.getReturnType() == void.class) {
                    	rubyFunctionName += "?";
                    }
                    outputStream.print("    @"+rubyFunctionName+"(self.class, '" + methodName + "'");
                    for (int i = 0; i < params.length; i++) {
                        String name = params[i].getName();
                        Class<?> pType = params[i].getType();
                        if (!isExcludedFromClassDefPublication(pType, name)) {
                            outputStream.print(", :" + name + " => " + name);
                        }
                    }
                    outputStream.println(")\n  end");
                }

                public void superClass(String superClass) throws IOException {
                    if (Arrays.binarySearch(classes, superClass) > -1) {
                        outputStream.print(" < " + superClass);
                    }
                }
            };

            for (String clazz : classes) {
                outputStream.print("class " + clazz);
                super.visitClass(clazz, mapv);
                outputStream.println("\n  def initialize " + init);
                outputStream.println("    @" + init + " = " + init);
                outputStream.println("  end\n");
                outputStream.println("\nend");
            }


        } else {
            super.respond(req, resp, pathInfo);
        }
    }

    /**
     * Some parameter types are excluded from being published in Ruby classdef fragments
     * @param type the type that is possibly excluded
     * @param name the name of the param that is possibly excluded
     * @return is or is not excluded
     */
    protected boolean isExcludedFromClassDefPublication(Class<?> type, String name) {
        return type.getName().startsWith("javax.servlet");
    }

}
