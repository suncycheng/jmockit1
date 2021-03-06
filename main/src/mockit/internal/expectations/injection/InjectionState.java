/*
 * Copyright (c) 2006-2015 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.injection;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.*;
import javax.inject.*;
import javax.servlet.*;

import mockit.internal.expectations.mocking.*;
import mockit.internal.state.*;
import mockit.internal.util.*;
import static mockit.internal.expectations.injection.InjectionPoint.*;

/**
 * Holds state used throughout the injection process while it's in progress for a given set of tested objects.
 */
final class InjectionState
{
   @Nonnull private static final Map<Object, Object> globalDependencies = new ConcurrentHashMap<Object, Object>(2);
   @Nonnull private final Map<Object, Object> testedObjects;
   @Nonnull private final Map<Object, Object> instantiatedDependencies;
   @Nonnull private List<MockedType> injectables;
   @Nonnull private List<MockedType> consumedInjectables;
   @Nonnull final LifecycleMethods lifecycleMethods;
   private GenericTypeReflection testedTypeReflection;
   private Object currentTestClassInstance;
   Type typeOfInjectionPoint;

   InjectionState()
   {
      testedObjects = new HashMap<Object, Object>();
      instantiatedDependencies = new HashMap<Object, Object>();
      injectables = Collections.emptyList();
      consumedInjectables = new ArrayList<MockedType>();
      lifecycleMethods = new LifecycleMethods();
   }

   void buildListsOfInjectables(@Nonnull Object testClassInstance, @Nonnull List<MockedType> injectableFields)
   {
      currentTestClassInstance = testClassInstance;
      injectables = new ArrayList<MockedType>(injectableFields);

      ParameterTypeRedefinitions paramTypeRedefs = TestRun.getExecutingTest().getParameterRedefinitions();

      if (paramTypeRedefs != null) {
         injectables.addAll(paramTypeRedefs.getInjectableParameters());
      }

      getServletConfigForInitMethodsIfAny(testClassInstance);
   }

   private void getServletConfigForInitMethodsIfAny(@Nonnull Object testClassInstance)
   {
      if (SERVLET_CLASS != null) {
         for (MockedType injectable : injectables) {
            if (injectable.declaredType == ServletConfig.class) {
               lifecycleMethods.servletConfig = injectable.getValueToInject(testClassInstance);
               break;
            }
         }
      }
   }

   void discardInjectablesFromLowerTestClassHierarchyLevels(@Nonnull Class<?> testSuperClass)
   {
      Iterator<MockedType> itr = injectables.iterator();

      while (itr.hasNext()) {
         MockedType injectable = itr.next();
         Field injectableField = injectable.field;

         if (injectableField == null || !injectableField.getDeclaringClass().isAssignableFrom(testSuperClass)) {
            itr.remove();
         }
      }
   }

   public Object getCurrentTestClassInstance() { return currentTestClassInstance; }

   void setTestedField(@Nonnull Field testedField) { testedTypeReflection = new GenericTypeReflection(testedField); }

   void setTypeOfInjectionPoint(@Nonnull Type typeOfInjectionPoint)
   {
      this.typeOfInjectionPoint = typeOfInjectionPoint;
   }

   private boolean hasSameTypeAsInjectionPoint(@Nonnull MockedType injectable)
   {
      return isSameTypeAsInjectionPoint(injectable.declaredType);
   }

   boolean isSameTypeAsInjectionPoint(@Nonnull Type injectableType)
   {
      if (testedTypeReflection.areMatchingTypes(typeOfInjectionPoint, injectableType)) {
         return true;
      }

      if (INJECT_CLASS != null && typeOfInjectionPoint instanceof ParameterizedType) {
         ParameterizedType parameterizedType = (ParameterizedType) typeOfInjectionPoint;

         if (parameterizedType.getRawType() == Provider.class) {
            Type providedType = parameterizedType.getActualTypeArguments()[0];
            return providedType.equals(injectableType);
         }
      }

      return false;
   }

   @Nullable
   MockedType findNextInjectableForInjectionPoint()
   {
      for (MockedType injectable : injectables) {
         if (hasSameTypeAsInjectionPoint(injectable) && !consumedInjectables.contains(injectable)) {
            return injectable;
         }
      }

      return null;
   }

   @Nonnull
   List<MockedType> findInjectablesByType()
   {
      List<MockedType> found = new ArrayList<MockedType>();

      for (MockedType injectable : injectables) {
         if (hasSameTypeAsInjectionPoint(injectable) && !consumedInjectables.contains(injectable)) {
            found.add(injectable);
         }
      }

      return found;
   }

   @Nullable
   MockedType findInjectableByTypeAndOptionallyName(@Nonnull String nameOfInjectionPoint)
   {
      MockedType found = null;

      for (MockedType injectable : injectables) {
         if (hasSameTypeAsInjectionPoint(injectable)) {
            if (nameOfInjectionPoint.equals(injectable.mockId)) {
               return injectable;
            }

            if (found == null) {
               found = injectable;
            }
         }
      }

      return found;
   }

   @Nullable
   MockedType findInjectableByTypeAndName(@Nonnull String nameOfInjectionPoint)
   {
      for (MockedType injectable : injectables) {
         if (hasSameTypeAsInjectionPoint(injectable) && nameOfInjectionPoint.equals(injectable.mockId)) {
            return injectable;
         }
      }

      return null;
   }

   @Nullable
   Object getValueToInject(@Nonnull MockedType injectable)
   {
      if (consumedInjectables.contains(injectable)) {
         return null;
      }

      Object value = injectable.getValueToInject(currentTestClassInstance);

      if (value != null) {
         consumedInjectables.add(injectable);
      }

      return value;
   }

   void resetConsumedInjectables()
   {
      consumedInjectables.clear();
   }

   @Nonnull
   List<MockedType> saveConsumedInjectables()
   {
      List<MockedType> previousConsumedInjectables = consumedInjectables;
      consumedInjectables = new ArrayList<MockedType>();
      return previousConsumedInjectables;
   }

   void restoreConsumedInjectables(@Nonnull List<MockedType> previousConsumedInjectables)
   {
      consumedInjectables = previousConsumedInjectables;
   }

   @Nullable
   Object getTestedObject(@Nonnull Object key) { return testedObjects.get(key); }

   void saveTestedObject(@Nonnull Object key, @Nonnull Object testedObject) { testedObjects.put(key, testedObject); }

   @Nullable
   Object getTestedInstance(@Nonnull Object key) { return instantiatedDependencies.get(key); }

   @SuppressWarnings("unchecked")
   @Nullable
   <D> D getGlobalDependency(@Nonnull Object key) { return (D) globalDependencies.get(key); }

   @Nullable
   Object getInstantiatedDependency(@Nonnull Object dependencyKey)
   {
      Object testedObject = testedObjects.get(dependencyKey);

      if (testedObject != null) {
         return testedObject;
      }

      Object dependency = instantiatedDependencies.get(dependencyKey);

      if (dependency == null) {
         dependency = globalDependencies.get(dependencyKey);
      }

      return dependency;
   }

   void saveInstantiatedDependency(@Nonnull Object dependencyKey, @Nonnull Object dependency)
   {
      instantiatedDependencies.put(dependencyKey, dependency);
   }

   void saveGlobalDependency(@Nonnull Object dependencyKey, @Nonnull Object dependency)
   {
      globalDependencies.put(dependencyKey, dependency);
   }

   void clearTestedObjectsAndInstantiatedDependencies()
   {
      testedObjects.clear();
      instantiatedDependencies.clear();
   }
}
