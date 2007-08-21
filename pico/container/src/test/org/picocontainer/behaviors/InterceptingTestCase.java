/*****************************************************************************
 * Copyright (C) PicoContainer Organization. All rights reserved.            *
 * ------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the BSD      *
 * style license a copy of which has been included with this distribution in *
 * the LICENSE.txt file.                                                     *
 *                                                                           *
 * Original code by                                                          *
 *****************************************************************************/
package org.picocontainer.behaviors;

import org.picocontainer.DefaultPicoContainer;

import junit.framework.TestCase;

public class InterceptingTestCase extends TestCase {

    public static interface Foo {
        void one();
        String two(String a, int b);
    }

    public static class FooImpl implements Foo {
        private StringBuilder sb;

        public FooImpl(StringBuilder sb) {
            this.sb = sb;
        }

        public void one() {
            sb.append("call-one(),");
        }

        public String two(String a, int b) {
            sb.append("call-two('"+a+"',"+b+"),");
            return "two";
        }
    }

    public void testPreAndPostObservation() {
        final StringBuilder sb = new StringBuilder();
        Interception interception = new Interception();
        final Interception.Interceptor interceptor = interception.interceptor();
        interception.pre(Foo.class, new Foo() {
            public void one() {
                sb.append("pre-one(),");
            }

            public String two(String a, int b) {
                sb.append("pre-two('"+a+"',"+b+"),");
                return null;
            }
        });
        interception.post(Foo.class, new Foo() {
            public void one() {
                sb.append("post-one(),");
            }

            public String two(String a, int b) {
                assertEquals("two", interceptor.getOriginalRetVal());
                sb.append("post-two('"+a+"',"+b+"),");
                return null;
            }
        });

        DefaultPicoContainer pico = new DefaultPicoContainer(interception);
        pico.addComponent(StringBuilder.class, sb);
        pico.addComponent(Foo.class, FooImpl.class);
        Foo foo = pico.getComponent(Foo.class);
        assertNotNull(foo);
        foo.one();
        assertEquals("two", foo.two("hello", 99));
        assertEquals("pre-one(),call-one(),post-one(),pre-two('hello',99),call-two('hello',99),post-two('hello',99),", sb.toString());
        assertEquals("Intercepted:ConstructorInjector-interface org.picocontainer.behaviors.InterceptingTestCase$Foo", pico.getComponentAdapter(Foo.class).toString());
    }

    public void testPreCanBlockInvocationWithAlternateReturnValue() {
        final StringBuilder sb = new StringBuilder();
        final Interception interception = new Interception();
        final Interception.Interceptor interceptor = interception.interceptor();
        interception.pre(Foo.class, new Foo() {
            public void one() {
                interceptor.veto();
                sb.append("veto-one(),");
            }

            public String two(String a, int b) {
                interceptor.veto();
                sb.append("veto-two('"+a+"',"+b+"),");
                return "isVetoed";
            }
        });

        DefaultPicoContainer pico = new DefaultPicoContainer(interception);
        pico.addComponent(Foo.class, FooImpl.class);
        pico.addComponent(StringBuilder.class, sb);
        Foo foo = pico.getComponent(Foo.class);
        assertNotNull(foo);
        foo.one();
        assertEquals("isVetoed", foo.two("hello", 99));
        assertEquals("veto-one(),veto-two('hello',99),", sb.toString());
        assertEquals("Intercepted:ConstructorInjector-interface org.picocontainer.behaviors.InterceptingTestCase$Foo", pico.getComponentAdapter(Foo.class).toString());
    }

    public void testOverrideOfReturnValue() {
        final StringBuilder sb = new StringBuilder();
        final Interception interception = new Interception();
        final Interception.Interceptor interceptor = interception.interceptor();
        interception.pre(Foo.class, new Foo() {
            public void one() {
                sb.append("pre-one(),");
            }

            public String two(String a, int b) {
                sb.append("pre-two('"+a+"',"+b+"),");
                return null;
            }
        });
        interception.post(Foo.class, new Foo() {
            public void one() {
                interceptor.override();
                sb.append("override-one(),");
             }

            public String two(String a, int b) {
                interceptor.override();
                sb.append("override-two('"+a+"',"+b+"),");
                return "x";
            }
        });

        DefaultPicoContainer pico = new DefaultPicoContainer(interception);
        pico.addComponent(Foo.class, FooImpl.class);
        pico.addComponent(StringBuilder.class, sb);
        Foo foo = pico.getComponent(Foo.class);
        assertNotNull(foo);
        foo.one();
        assertEquals("x", foo.two("hello", 99));
        assertEquals("pre-one(),call-one(),override-one(),pre-two('hello',99),call-two('hello',99),override-two('hello',99),", sb.toString());
        assertEquals("Intercepted:ConstructorInjector-interface org.picocontainer.behaviors.InterceptingTestCase$Foo", pico.getComponentAdapter(Foo.class).toString());
    }

    public void testNothingHappensIfNoPreOrPost() {
        final StringBuilder sb = new StringBuilder();
        DefaultPicoContainer pico = new DefaultPicoContainer(new Interception());
        pico.addComponent(Foo.class, FooImpl.class);
        pico.addComponent(StringBuilder.class, sb);
        Foo foo = pico.getComponent(Foo.class);
        assertNotNull(foo);
        foo.one();
        assertEquals("two", foo.two("hello", 99));
        assertEquals("call-one(),call-two('hello',99),", sb.toString());
        assertEquals("Intercepted:ConstructorInjector-interface org.picocontainer.behaviors.InterceptingTestCase$Foo", pico.getComponentAdapter(Foo.class).toString());
    }



}