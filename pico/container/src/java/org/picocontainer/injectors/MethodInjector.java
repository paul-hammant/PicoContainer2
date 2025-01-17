/*****************************************************************************
 * Copyright (c) PicoContainer Organization. All rights reserved.            *
 * ------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the BSD      *
 * style license a copy of which has been included with this distribution in *
 * the LICENSE.txt file.                                                     *
 *                                                                           *
 *****************************************************************************/

package org.picocontainer.injectors;

import org.picocontainer.ComponentMonitor;
import org.picocontainer.Parameter;
import org.picocontainer.PicoCompositionException;
import org.picocontainer.PicoContainer;
import org.picocontainer.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * Injection will happen through a single method for the component.
 *
 * Most likely it is a method called 'inject', though that can be overridden.
 *
 * @author Paul Hammant
 * @author Aslak Helles&oslash;y
 * @author Jon Tirs&eacute;n
 * @author Zohar Melamed
 * @author J&ouml;rg Schaible
 * @author Mauro Talevi
 */
@SuppressWarnings("serial")
public abstract class MethodInjector<T> extends SingleMemberInjector<T> {
    private transient ThreadLocalCyclicDependencyGuard instantiationGuard;
    private final String methodName;

    /**
     * Creates a MethodInjector
     *
     * @param componentKey            the search key for this implementation
     * @param componentImplementation the concrete implementation
     * @param parameters              the parameters to use for the initialization
     * @param monitor                 the component monitor used by this addAdapter
     * @param methodName              the method name
     * @param useNames                use argument names when looking up dependencies
     * @throws AbstractInjector.NotConcreteRegistrationException
     *                              if the implementation is not a concrete class.
     * @throws NullPointerException if one of the parameters is <code>null</code>
     */
    public MethodInjector(final Object componentKey, final Class componentImplementation, Parameter[] parameters, ComponentMonitor monitor,
                          String methodName, boolean useNames) throws AbstractInjector.NotConcreteRegistrationException {
        super(componentKey, componentImplementation, parameters, monitor, useNames);
        this.methodName = methodName;
    }

    protected abstract Method getInjectorMethod();

    @Override
    public T getComponentInstance(final PicoContainer container, @SuppressWarnings("unused") Type into) throws PicoCompositionException {
        if (instantiationGuard == null) {
            instantiationGuard = new ThreadLocalCyclicDependencyGuard() {
                @Override
                @SuppressWarnings("synthetic-access")
                public Object run(Object instance) {
                    Method method = getInjectorMethod();
                    T inst = null;
                    ComponentMonitor componentMonitor = currentMonitor();
                    try { // TODO .. instantiating() ???
                        componentMonitor.instantiating(container, MethodInjector.this, null);
                        long startTime = System.currentTimeMillis();
                        Object[] methodParameters = null;
                        inst = getComponentImplementation().newInstance();
                        if (method != null) {
                            methodParameters = getMemberArguments(guardedContainer, method);
                            invokeMethod(method, methodParameters, inst, container);
                        }
                        componentMonitor.instantiated(container, MethodInjector.this,
                                                      null, inst, methodParameters, System.currentTimeMillis() - startTime);
                        return inst;
                    } catch (InstantiationException e) {
                        return caughtInstantiationException(componentMonitor, null, e, container);
                    } catch (IllegalAccessException e) {
                        return caughtIllegalAccessException(componentMonitor, method, inst, e);

                    }
                }
            };
        }
        instantiationGuard.setGuardedContainer(container);
        return (T) instantiationGuard.observe(getComponentImplementation(), null);
    }

    protected Object[] getMemberArguments(PicoContainer container, final Method method) {
        return super.getMemberArguments(container, method, method.getParameterTypes(), getBindings(method.getParameterAnnotations()));
    }

    @Override
    public Object decorateComponentInstance(final PicoContainer container, @SuppressWarnings("unused") final Type into, final T instance) {
        if (instantiationGuard == null) {
            instantiationGuard = new ThreadLocalCyclicDependencyGuard() {
                @Override
                @SuppressWarnings("synthetic-access")
                public Object run(Object inst) {
                    Method method = getInjectorMethod();
                    if (method != null && method.getDeclaringClass().isAssignableFrom(inst.getClass())) {
                        Object[] methodParameters = getMemberArguments(guardedContainer, method);
                        return invokeMethod(method, methodParameters, (T) inst, container);
                    }
                    return null;
                }
            };
        }
        instantiationGuard.setGuardedContainer(container);
        Object o = instantiationGuard.observe(getComponentImplementation(), instance);
        return o;
    }

    private Object invokeMethod(Method method, Object[] methodParameters, T instance, PicoContainer container) {
        try {
            Object rv = currentMonitor().invoking(container, MethodInjector.this, (Member) method, instance, methodParameters);
            if (rv == ComponentMonitor.KEEP) {
                long str = System.currentTimeMillis();
                rv = method.invoke(instance, methodParameters);
                currentMonitor().invoked(container, MethodInjector.this, method, instance, System.currentTimeMillis() - str, methodParameters, rv);
            }
            return rv;
        } catch (IllegalAccessException e) {
            return caughtIllegalAccessException(currentMonitor(), method, instance, e);
        } catch (InvocationTargetException e) {
            currentMonitor().invocationFailed(method, instance, e);
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            } else if (e.getTargetException() instanceof Error) {
                throw (Error) e.getTargetException();
            }
            throw new PicoCompositionException(e);
        }
    }


    @Override
    public void verify(final PicoContainer container) throws PicoCompositionException {
        if (verifyingGuard == null) {
            verifyingGuard = new ThreadLocalCyclicDependencyGuard() {
                @Override
                public Object run(Object instance) {
                    final Method method = getInjectorMethod();
                    final Class[] parameterTypes = method.getParameterTypes();
                    final Parameter[] currentParameters = parameters != null ? parameters : createDefaultParameters(parameterTypes.length);
                    for (int i = 0; i < currentParameters.length; i++) {
                        currentParameters[i].verify(container, MethodInjector.this, parameterTypes[i],
                            new ParameterNameBinding(method, i), useNames(),
                                                    getBindings(method.getParameterAnnotations())[i]);
                    }
                    return null;
                }
            };
        }
        verifyingGuard.setGuardedContainer(container);
        verifyingGuard.observe(getComponentImplementation(), null);
    }

    @Override
    protected boolean isNullParamAllowed(AccessibleObject member, int i) {
        Annotation[] annotations = ((Method) member).getParameterAnnotations()[i];
        for (Annotation annotation : annotations) {
            if (annotation instanceof Nullable) {
                return true;
            }
        }
        return false;
    }


    public static class ByReflectionMethod extends MethodInjector {
        private final Method injectionMethod;

        public ByReflectionMethod(Object componentKey, Class componentImplementation, Parameter[] parameters, ComponentMonitor monitor, Method injectionMethod, boolean useNames) throws NotConcreteRegistrationException {
            super(componentKey, componentImplementation, parameters, monitor, null, useNames);
            this.injectionMethod = injectionMethod;
        }
        
        @Override
        protected Method getInjectorMethod() {
            return injectionMethod;
        }
        
        @Override
        public String getDescriptor() {
            return "MethodInjector.ByReflectionMethod[" + injectionMethod + "]-";
        }

    }

    public static class ByMethodName extends MethodInjector {
        private Set<String> injectionMethodNames;

        public ByMethodName(Object componentKey, Class componentImplementation, Parameter[] parameters, ComponentMonitor monitor, Set<String> injectionMethodNames, boolean useNames) throws NotConcreteRegistrationException {
            super(componentKey, componentImplementation, parameters, monitor, null, useNames);
            ByMethodName.this.injectionMethodNames = injectionMethodNames;
        }

        @Override
        protected Method getInjectorMethod() {
            for (Method method : super.getComponentImplementation().getMethods()) {
                if (injectionMethodNames.contains(method.getName())) {
                    return method;
                }
            }
            return null;
        }


        @Override
        public String getDescriptor() {
            return "MethodInjector.ByMethodName" + injectionMethodNames + "-";
        }

    }

}