package org.picocontainer.injectors;

import org.picocontainer.ComponentMonitor;
import org.picocontainer.NameBinding;
import org.picocontainer.Parameter;
import org.picocontainer.PicoCompositionException;
import org.picocontainer.PicoContainer;
import org.picocontainer.annotations.Bind;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Injection will happen iteratively after component instantiation
 */
public abstract class IterativeInjector<T> extends AbstractInjector<T> {

    private static final Object[] NONE = new Object[0];

    private transient ThreadLocalCyclicDependencyGuard instantiationGuard;
    protected transient List<AccessibleObject> injectionMembers;
    protected transient Type[] injectionTypes;
    protected transient Annotation[] bindings;

    private transient volatile boolean initialized;

    /**
     * Constructs a IterativeInjector
     *
     * @param componentKey            the search key for this implementation
     * @param componentImplementation the concrete implementation
     * @param parameters              the parameters to use for the initialization
     * @param monitor                 the component monitor used by this addAdapter
     * @param useNames                use argument names when looking up dependencies
     * @throws org.picocontainer.injectors.AbstractInjector.NotConcreteRegistrationException
     *                              if the implementation is not a concrete class.
     * @throws NullPointerException if one of the parameters is <code>null</code>
     */
    public IterativeInjector(final Object componentKey, final Class componentImplementation, Parameter[] parameters, ComponentMonitor monitor,
                             boolean useNames) throws  NotConcreteRegistrationException {
        super(componentKey, componentImplementation, parameters, monitor, useNames);
    }

    protected Constructor getConstructor()  {
        Object retVal = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    return getComponentImplementation().getConstructor((Class[])null);
                } catch (NoSuchMethodException e) {
                    return new PicoCompositionException(e);
                } catch (SecurityException e) {
                    return new PicoCompositionException(e);
                }
            }
        });
        if (retVal instanceof Constructor) {
            return (Constructor) retVal;
        } else {
            throw (PicoCompositionException) retVal;
        }
    }

    private Parameter[] getMatchingParameterListForSetters(PicoContainer container) throws PicoCompositionException {
        if (initialized == false) {
            synchronized (this) {
                if (initialized == false) {
                    initializeInjectionMembersAndTypeLists();
                }
            }
        }

        final List<Object> matchingParameterList = new ArrayList<Object>(Collections.nCopies(injectionMembers.size(), null));

        final Parameter[] currentParameters = parameters != null ? parameters : createDefaultParameters(injectionTypes.length);
        final Set<Integer> nonMatchingParameterPositions = matchParameters(container, matchingParameterList, currentParameters);

        final Set<Type> unsatisfiableDependencyTypes = new HashSet<Type>();
        final List<AccessibleObject> unsatisfiableDependencyMembers = new ArrayList<AccessibleObject>();
        for (int i = 0; i < matchingParameterList.size(); i++) {
            if (matchingParameterList.get(i) == null) {
                unsatisfiableDependencyTypes.add(injectionTypes[i]);
                unsatisfiableDependencyMembers.add(injectionMembers.get(i));
            }
        }
        if (unsatisfiableDependencyTypes.size() > 0) {
            unsatisfiedDependencies(container, unsatisfiableDependencyTypes, unsatisfiableDependencyMembers);
        } else if (nonMatchingParameterPositions.size() > 0) {
            throw new PicoCompositionException("Following parameters do not match any of the injectionMembers for " + getComponentImplementation() + ": " + nonMatchingParameterPositions.toString());
        }
        return matchingParameterList.toArray(new Parameter[matchingParameterList.size()]);
    }

    private Set<Integer> matchParameters(PicoContainer container, List<Object> matchingParameterList, Parameter[] currentParameters) {
        Set<Integer> unmatchedParameters = new HashSet<Integer>();
        for (int i = 0; i < currentParameters.length; i++) {
            if (!matchParameter(container, matchingParameterList, currentParameters[i])) {
                unmatchedParameters.add(i);
            }
        }
        return unmatchedParameters;
    }

    private boolean matchParameter(PicoContainer container, List<Object> matchingParameterList, Parameter parameter) {
        for (int j = 0; j < injectionTypes.length; j++) {
            Object o = matchingParameterList.get(j);
            try {
                if (o == null && parameter.resolve(container, this, null, injectionTypes[j],
                        makeParameterNameImpl(injectionMembers.get(j)),
                        useNames(), bindings[j]).isResolved()) {
                    matchingParameterList.set(j, parameter);
                    return true;
                }
            } catch (AmbiguousComponentResolutionException e) {
                e.setMember(injectionMembers.get(j));
                throw e;
            }
        }
        return false;
    }

    protected NameBinding makeParameterNameImpl(AccessibleObject member) {
        return new ParameterNameBinding(member, 0);
    }

    protected abstract void unsatisfiedDependencies(PicoContainer container, Set<Type> unsatisfiableDependencyTypes, List<AccessibleObject> unsatisfiableDependencyMembers);

    public T getComponentInstance(final PicoContainer container, Type into) throws PicoCompositionException {
        final Constructor constructor = getConstructor();
        if (instantiationGuard == null) {
            instantiationGuard = new ThreadLocalCyclicDependencyGuard() {
                public Object run(Object instance) {
                    final Parameter[] matchingParameters = getMatchingParameterListForSetters(guardedContainer);
                    Object componentInstance = makeInstance(container, constructor, currentMonitor());
                    return decorateComponentInstance(matchingParameters, currentMonitor(), componentInstance, container, guardedContainer);
                }
            };
        }
        instantiationGuard.setGuardedContainer(container);
        return (T) instantiationGuard.observe(getComponentImplementation(), null);
    }

    private Object decorateComponentInstance(Parameter[] matchingParameters, ComponentMonitor componentMonitor, Object componentInstance, PicoContainer container, PicoContainer guardedContainer) {
        AccessibleObject member = null;
        Object injected[] = new Object[injectionMembers.size()];
        Object lastReturn = null;
        try {
            for (int i = 0; i < injectionMembers.size(); i++) {
                member = injectionMembers.get(i);
                if (matchingParameters[i] != null) {
                    Object toInject = matchingParameters[i].resolve(guardedContainer, this, null, injectionTypes[i],
                                                                            makeParameterNameImpl(injectionMembers.get(i)),
                                                                            useNames(), bindings[i]).resolveInstance();
                    Object rv = componentMonitor.invoking(container, this, (Member) member, componentInstance, new Object[] {toInject});
                    if (rv == ComponentMonitor.KEEP) {
                        long str = System.currentTimeMillis();
                        lastReturn = injectIntoMember(member, componentInstance, toInject);
                        componentMonitor.invoked(container, this, (Member) member, componentInstance, System.currentTimeMillis() - str, new Object[] {toInject}, lastReturn);
                    } else {
                        lastReturn = rv;
                    }
                    injected[i] = toInject;
                }
            }
            return memberInvocationReturn(lastReturn, member, componentInstance);
        } catch (InvocationTargetException e) {
            return caughtInvocationTargetException(componentMonitor, (Member) member, componentInstance, e);
        } catch (IllegalAccessException e) {
            return caughtIllegalAccessException(componentMonitor, (Member) member, componentInstance, e);
        }
    }

    protected abstract Object memberInvocationReturn(Object lastReturn, AccessibleObject member, Object instance);

    private Object makeInstance(PicoContainer container, Constructor constructor, ComponentMonitor componentMonitor) {
        long startTime = System.currentTimeMillis();
        Constructor constructorToUse = componentMonitor.instantiating(container,
                                                                      IterativeInjector.this, constructor);
        Object componentInstance;
        try {
            componentInstance = newInstance(constructorToUse, null);
        } catch (InvocationTargetException e) {
            componentMonitor.instantiationFailed(container, IterativeInjector.this, constructorToUse, e);
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException)e.getTargetException();
            } else if (e.getTargetException() instanceof Error) {
                throw (Error)e.getTargetException();
            }
            throw new PicoCompositionException(e.getTargetException());
        } catch (InstantiationException e) {
            return caughtInstantiationException(componentMonitor, constructor, e, container);
        } catch (IllegalAccessException e) {
            return caughtIllegalAccessException(componentMonitor, constructor, e, container);
        }
        componentMonitor.instantiated(container,
                                      IterativeInjector.this,
                                      constructorToUse,
                                      componentInstance,
                                      NONE,
                                      System.currentTimeMillis() - startTime);
        return componentInstance;
    }

    @Override
    public Object decorateComponentInstance(final PicoContainer container, Type into, final T instance) {
        if (instantiationGuard == null) {
            instantiationGuard = new ThreadLocalCyclicDependencyGuard() {
                public Object run(Object inst) {
                    final Parameter[] matchingParameters = getMatchingParameterListForSetters(guardedContainer);
                    return decorateComponentInstance(matchingParameters, currentMonitor(), inst, container, guardedContainer);
                }
            };
        }
        instantiationGuard.setGuardedContainer(container);
        return instantiationGuard.observe(getComponentImplementation(), instance);
    }

    protected abstract Object injectIntoMember(AccessibleObject member, Object componentInstance, Object toInject) throws IllegalAccessException, InvocationTargetException;

    @Override
    public void verify(final PicoContainer container) throws PicoCompositionException {
        if (verifyingGuard == null) {
            verifyingGuard = new ThreadLocalCyclicDependencyGuard() {
                public Object run(Object instance) {
                    final Parameter[] currentParameters = getMatchingParameterListForSetters(guardedContainer);
                    for (int i = 0; i < currentParameters.length; i++) {
                        currentParameters[i].verify(container, IterativeInjector.this, injectionTypes[i],
                                                    makeParameterNameImpl(injectionMembers.get(i)), useNames(), bindings[i]);
                    }
                    return null;
                }
            };
        }
        verifyingGuard.setGuardedContainer(container);
        verifyingGuard.observe(getComponentImplementation(), null);
    }

    protected void initializeInjectionMembersAndTypeLists() {
        injectionMembers = new ArrayList<AccessibleObject>();
        Set<String> injectionMemberNames = new HashSet<String>();
        List<Annotation> bingingIds = new ArrayList<Annotation>();
        final List<String> nameList = new ArrayList<String>();
        final List<Type> typeList = new ArrayList<Type>();
        final Method[] methods = getMethods();
        for (final Method method : methods) {
            final Type[] parameterTypes = method.getGenericParameterTypes();
            fixGenericParameterTypes(method, parameterTypes);

            String methodSignature = crudeMethodSignature(method);

            // We're only interested if there is only one parameter ...
            if (parameterTypes.length == 1) {
                boolean isInjector = isInjectorMethod(method);
                // ... and the method name is bean-style.
                // We're also not interested in dupes from parent classes (not all JDK impls)
                if (isInjector && !injectionMemberNames.contains(methodSignature)) {
                    injectionMembers.add(method);
                    injectionMemberNames.add(methodSignature);
                    nameList.add(getName(method));
                    typeList.add(box(parameterTypes[0]));
                    bingingIds.add(getBindings(method, 0));
                }
            }
        }
        injectionTypes = typeList.toArray(new Type[0]);
        bindings = bingingIds.toArray(new Annotation[0]);
        initialized = true;
    }

    public static String crudeMethodSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getReturnType().getName());
        sb.append(method.getName());
        for (Class<?> pType : method.getParameterTypes()) {
            sb.append(pType.getName());
        }
        return sb.toString();
    }

    protected String getName(Method method) {
        return null;
    }

    private void fixGenericParameterTypes(Method method, Type[] parameterTypes) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Type parameterType = parameterTypes[i];
            if (parameterType instanceof TypeVariable) {
                parameterTypes[i] = method.getParameterTypes()[i];
            }
        }
    }


    private Annotation getBindings(Method method, int i) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        if (parameterAnnotations.length >= i +1 ) {
            Annotation[] o = parameterAnnotations[i];
            for (Annotation annotation : o) {
                if (annotation.annotationType().getAnnotation(Bind.class) != null) {
                    return annotation;
                }
            }
            return null;

        }
        //TODO - what's this ?
        if (parameterAnnotations != null) {
            //return ((Bind) method.getAnnotation(Bind.class)).id();
        }
        return null;

    }

    protected boolean isInjectorMethod(Method method) {
        return false;
    }

    private Method[] getMethods() {
        return (Method[]) AccessController.doPrivileged(new PrivilegedAction() {
            public Object run() {
                return getComponentImplementation().getMethods();
            }
        });
    }

}
