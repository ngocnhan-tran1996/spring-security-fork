/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.authorization;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.NonNull;
import org.springframework.security.authorization.method.AuthorizationAdvisor;
import org.springframework.security.authorization.method.AuthorizationManagerAfterMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerAfterReactiveMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeReactiveMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizeReturnObject;
import org.springframework.security.authorization.method.AuthorizeReturnObjectMethodInterceptor;
import org.springframework.security.authorization.method.PostFilterAuthorizationMethodInterceptor;
import org.springframework.security.authorization.method.PostFilterAuthorizationReactiveMethodInterceptor;
import org.springframework.security.authorization.method.PreFilterAuthorizationMethodInterceptor;
import org.springframework.security.authorization.method.PreFilterAuthorizationReactiveMethodInterceptor;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * A proxy factory for applying authorization advice to an arbitrary object.
 *
 * <p>
 * For example, consider a non-Spring-managed object {@code Foo}: <pre>
 *     class Foo {
 *         &#064;PreAuthorize("hasAuthority('bar:read')")
 *         String bar() { ... }
 *     }
 * </pre>
 *
 * Use {@link AuthorizationAdvisorProxyFactory} to wrap the instance in Spring Security's
 * {@link org.springframework.security.access.prepost.PreAuthorize} method interceptor
 * like so:
 *
 * <pre>
 *     AuthorizationProxyFactory proxyFactory = AuthorizationAdvisorProxyFactory.withDefaults();
 *     Foo foo = new Foo();
 *     foo.bar(); // passes
 *     Foo securedFoo = proxyFactory.proxy(foo);
 *     securedFoo.bar(); // access denied!
 * </pre>
 *
 * @author Josh Cummings
 * @since 6.3
 */
public final class AuthorizationAdvisorProxyFactory
		implements AuthorizationProxyFactory, Iterable<AuthorizationAdvisor> {

	private static final boolean isReactivePresent = ClassUtils.isPresent("reactor.core.publisher.Mono", null);

	private static final TargetVisitor DEFAULT_VISITOR = isReactivePresent
			? TargetVisitor.of(new ClassVisitor(), new ReactiveTypeVisitor(), new ContainerTypeVisitor())
			: TargetVisitor.of(new ClassVisitor(), new ContainerTypeVisitor());

	private static final TargetVisitor DEFAULT_VISITOR_SKIP_VALUE_TYPES = TargetVisitor.of(new ClassVisitor(),
			new IgnoreValueTypeVisitor(), DEFAULT_VISITOR);

	private List<AuthorizationAdvisor> advisors;

	private TargetVisitor visitor = DEFAULT_VISITOR;

	private AuthorizationAdvisorProxyFactory(List<AuthorizationAdvisor> advisors) {
		this.advisors = new ArrayList<>(advisors);
		this.advisors.add(new AuthorizeReturnObjectMethodInterceptor(this));
		setAdvisors(this.advisors);
	}

	/**
	 * Construct an {@link AuthorizationAdvisorProxyFactory} with the defaults needed for
	 * wrapping objects in Spring Security's pre-post method security support.
	 * @return an {@link AuthorizationAdvisorProxyFactory} for adding pre-post method
	 * security support
	 */
	public static AuthorizationAdvisorProxyFactory withDefaults() {
		List<AuthorizationAdvisor> advisors = new ArrayList<>();
		advisors.add(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
		advisors.add(AuthorizationManagerAfterMethodInterceptor.postAuthorize());
		advisors.add(new PreFilterAuthorizationMethodInterceptor());
		advisors.add(new PostFilterAuthorizationMethodInterceptor());
		return new AuthorizationAdvisorProxyFactory(advisors);
	}

	/**
	 * Construct an {@link AuthorizationAdvisorProxyFactory} with the defaults needed for
	 * wrapping objects in Spring Security's pre-post reactive method security support.
	 * @return an {@link AuthorizationAdvisorProxyFactory} for adding pre-post reactive
	 * method security support
	 */
	public static AuthorizationAdvisorProxyFactory withReactiveDefaults() {
		List<AuthorizationAdvisor> advisors = new ArrayList<>();
		advisors.add(AuthorizationManagerBeforeReactiveMethodInterceptor.preAuthorize());
		advisors.add(AuthorizationManagerAfterReactiveMethodInterceptor.postAuthorize());
		advisors.add(new PreFilterAuthorizationReactiveMethodInterceptor());
		advisors.add(new PostFilterAuthorizationReactiveMethodInterceptor());
		return new AuthorizationAdvisorProxyFactory(advisors);
	}

	/**
	 * Proxy an object to enforce authorization advice.
	 *
	 * <p>
	 * Proxies any instance of a non-final class or a class that implements more than one
	 * interface.
	 *
	 * <p>
	 * If {@code target} is an {@link Iterator}, {@link Collection}, {@link Array},
	 * {@link Map}, {@link Stream}, or {@link Optional}, then the element or value type is
	 * proxied.
	 *
	 * <p>
	 * If {@code target} is a {@link Class}, then {@link ProxyFactory#getProxyClass} is
	 * invoked instead.
	 * @param target the instance to proxy
	 * @return the proxied instance
	 */
	@Override
	public Object proxy(Object target) {
		if (target == null) {
			return null;
		}
		Object proxied = this.visitor.visit(this, target);
		if (proxied != null) {
			return proxied;
		}
		ProxyFactory factory = new ProxyFactory(target);
		for (Advisor advisor : this.advisors) {
			factory.addAdvisors(advisor);
		}
		factory.setProxyTargetClass(!Modifier.isFinal(target.getClass().getModifiers()));
		return factory.getProxy();
	}

	/**
	 * Add advisors that should be included to each proxy created.
	 *
	 * <p>
	 * All advisors are re-sorted by their advisor order.
	 * @param advisors the advisors to add
	 */
	public void setAdvisors(AuthorizationAdvisor... advisors) {
		this.advisors = new ArrayList<>(List.of(advisors));
		AnnotationAwareOrderComparator.sort(this.advisors);
	}

	/**
	 * Add advisors that should be included to each proxy created.
	 *
	 * <p>
	 * All advisors are re-sorted by their advisor order.
	 * @param advisors the advisors to add
	 */
	public void setAdvisors(Collection<AuthorizationAdvisor> advisors) {
		this.advisors = new ArrayList<>(advisors);
		AnnotationAwareOrderComparator.sort(this.advisors);
	}

	/**
	 * Use this visitor to navigate the proxy target's hierarchy.
	 *
	 * <p>
	 * This can be helpful when you want a specialized behavior for a type or set of
	 * types. For example, if you want to have this factory skip primitives and wrappers,
	 * then you can do:
	 *
	 * <pre>
	 * 	AuthorizationAdvisorProxyFactory proxyFactory = new AuthorizationAdvisorProxyFactory();
	 * 	proxyFactory.setTargetVisitor(AuthorizationAdvisorProxyFactory.DEFAULT_VISITOR_IGNORE_VALUE_TYPES);
	 * </pre>
	 * @param visitor the visitor to use to introduce specialized behavior for a type
	 */
	public void setTargetVisitor(TargetVisitor visitor) {
		Assert.notNull(visitor, "delegate cannot be null");
		this.visitor = visitor;
	}

	@Override
	@NonNull
	public Iterator<AuthorizationAdvisor> iterator() {
		return this.advisors.iterator();
	}

	/**
	 * An interface to handle how the {@link AuthorizationAdvisorProxyFactory} should step
	 * through the target's object hierarchy.
	 *
	 * @author Josh Cummings
	 * @since 6.3
	 * @see AuthorizationAdvisorProxyFactory#setTargetVisitor
	 */
	public interface TargetVisitor {

		/**
		 * Visit and possibly proxy this object.
		 *
		 * <p>
		 * Visiting may take the form of walking down this object's hierarchy and proxying
		 * sub-objects.
		 *
		 * <p>
		 * An example is a visitor that proxies the elements of a {@link List} instead of
		 * the list itself
		 *
		 * <p>
		 * Returning {@code null} implies that this visitor does not want to proxy this
		 * object
		 * @param proxyFactory the proxy factory to delegate proxying to for any
		 * sub-objects
		 * @param target the object to proxy
		 * @return the visited (and possibly proxied) object
		 */
		Object visit(AuthorizationAdvisorProxyFactory proxyFactory, Object target);

		/**
		 * The default {@link TargetVisitor}, which will proxy {@link Class} instances as
		 * well as instances contained in reactive types (if reactor is present),
		 * collection types, and other container types like {@link Optional} and
		 * {@link Supplier}
		 */
		static TargetVisitor defaults() {
			return AuthorizationAdvisorProxyFactory.DEFAULT_VISITOR;
		}

		/**
		 * The default {@link TargetVisitor} that also skips any value types (for example,
		 * {@link String}, {@link Integer}). This is handy for annotations like
		 * {@link AuthorizeReturnObject} when used at the class level
		 */
		static TargetVisitor defaultsSkipValueTypes() {
			return AuthorizationAdvisorProxyFactory.DEFAULT_VISITOR_SKIP_VALUE_TYPES;
		}

		static TargetVisitor of(TargetVisitor... visitors) {
			return (proxyFactory, target) -> {
				for (TargetVisitor visitor : visitors) {
					Object result = visitor.visit(proxyFactory, target);
					if (result != null) {
						return result;
					}
				}
				return null;
			};
		}

	}

	private static final class IgnoreValueTypeVisitor implements TargetVisitor {

		@Override
		public Object visit(AuthorizationAdvisorProxyFactory proxyFactory, Object object) {
			if (ClassUtils.isSimpleValueType(object.getClass())) {
				return object;
			}
			return null;
		}

	}

	private static final class ClassVisitor implements TargetVisitor {

		@Override
		public Object visit(AuthorizationAdvisorProxyFactory proxyFactory, Object object) {
			if (object instanceof Class<?> targetClass) {
				ProxyFactory factory = new ProxyFactory();
				factory.setTargetClass(targetClass);
				factory.setInterfaces(ClassUtils.getAllInterfacesForClass(targetClass));
				factory.setProxyTargetClass(!Modifier.isFinal(targetClass.getModifiers()));
				for (Advisor advisor : proxyFactory) {
					factory.addAdvisors(advisor);
				}
				return factory.getProxyClass(getClass().getClassLoader());
			}
			return null;
		}

	}

	private static final class ContainerTypeVisitor implements TargetVisitor {

		@Override
		public Object visit(AuthorizationAdvisorProxyFactory proxyFactory, Object target) {
			if (target instanceof Iterator<?> iterator) {
				return proxyIterator(proxyFactory, iterator);
			}
			if (target instanceof Queue<?> queue) {
				return proxyQueue(proxyFactory, queue);
			}
			if (target instanceof List<?> list) {
				return proxyList(proxyFactory, list);
			}
			if (target instanceof SortedSet<?> set) {
				return proxySortedSet(proxyFactory, set);
			}
			if (target instanceof Set<?> set) {
				return proxySet(proxyFactory, set);
			}
			if (target.getClass().isArray()) {
				return proxyArray(proxyFactory, (Object[]) target);
			}
			if (target instanceof SortedMap<?, ?> map) {
				return proxySortedMap(proxyFactory, map);
			}
			if (target instanceof Iterable<?> iterable) {
				return proxyIterable(proxyFactory, iterable);
			}
			if (target instanceof Map<?, ?> map) {
				return proxyMap(proxyFactory, map);
			}
			if (target instanceof Stream<?> stream) {
				return proxyStream(proxyFactory, stream);
			}
			if (target instanceof Optional<?> optional) {
				return proxyOptional(proxyFactory, optional);
			}
			if (target instanceof Supplier<?> supplier) {
				return proxySupplier(proxyFactory, supplier);
			}
			return null;
		}

		@SuppressWarnings("unchecked")
		private <T> T proxyCast(AuthorizationProxyFactory proxyFactory, T target) {
			return (T) proxyFactory.proxy(target);
		}

		private <T> Iterable<T> proxyIterable(AuthorizationProxyFactory proxyFactory, Iterable<T> iterable) {
			return () -> proxyIterator(proxyFactory, iterable.iterator());
		}

		private <T> Iterator<T> proxyIterator(AuthorizationProxyFactory proxyFactory, Iterator<T> iterator) {
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					return iterator.hasNext();
				}

				@Override
				public T next() {
					return proxyCast(proxyFactory, iterator.next());
				}
			};
		}

		private <T> SortedSet<T> proxySortedSet(AuthorizationProxyFactory proxyFactory, SortedSet<T> set) {
			SortedSet<T> proxies = new TreeSet<>(set.comparator());
			for (T toProxy : set) {
				proxies.add(proxyCast(proxyFactory, toProxy));
			}
			try {
				set.clear();
				set.addAll(proxies);
				return proxies;
			}
			catch (UnsupportedOperationException ex) {
				return Collections.unmodifiableSortedSet(proxies);
			}
		}

		private <T> Set<T> proxySet(AuthorizationProxyFactory proxyFactory, Set<T> set) {
			Set<T> proxies = new LinkedHashSet<>(set.size());
			for (T toProxy : set) {
				proxies.add(proxyCast(proxyFactory, toProxy));
			}
			try {
				set.clear();
				set.addAll(proxies);
				return proxies;
			}
			catch (UnsupportedOperationException ex) {
				return Collections.unmodifiableSet(proxies);
			}
		}

		private <T> Queue<T> proxyQueue(AuthorizationProxyFactory proxyFactory, Queue<T> queue) {
			Queue<T> proxies = new LinkedList<>();
			for (T toProxy : queue) {
				proxies.add(proxyCast(proxyFactory, toProxy));
			}
			queue.clear();
			queue.addAll(proxies);
			return proxies;
		}

		private <T> List<T> proxyList(AuthorizationProxyFactory proxyFactory, List<T> list) {
			List<T> proxies = new ArrayList<>(list.size());
			for (T toProxy : list) {
				proxies.add(proxyCast(proxyFactory, toProxy));
			}
			try {
				list.clear();
				list.addAll(proxies);
				return proxies;
			}
			catch (UnsupportedOperationException ex) {
				return Collections.unmodifiableList(proxies);
			}
		}

		private Object[] proxyArray(AuthorizationProxyFactory proxyFactory, Object[] objects) {
			List<Object> retain = new ArrayList<>(objects.length);
			for (Object object : objects) {
				retain.add(proxyFactory.proxy(object));
			}
			Object[] proxies = (Object[]) Array.newInstance(objects.getClass().getComponentType(), retain.size());
			for (int i = 0; i < retain.size(); i++) {
				proxies[i] = retain.get(i);
			}
			return proxies;
		}

		private <K, V> SortedMap<K, V> proxySortedMap(AuthorizationProxyFactory proxyFactory, SortedMap<K, V> entries) {
			SortedMap<K, V> proxies = new TreeMap<>(entries.comparator());
			for (Map.Entry<K, V> entry : entries.entrySet()) {
				proxies.put(entry.getKey(), proxyCast(proxyFactory, entry.getValue()));
			}
			try {
				entries.clear();
				entries.putAll(proxies);
				return entries;
			}
			catch (UnsupportedOperationException ex) {
				return Collections.unmodifiableSortedMap(proxies);
			}
		}

		private <K, V> Map<K, V> proxyMap(AuthorizationProxyFactory proxyFactory, Map<K, V> entries) {
			Map<K, V> proxies = new LinkedHashMap<>(entries.size());
			for (Map.Entry<K, V> entry : entries.entrySet()) {
				proxies.put(entry.getKey(), proxyCast(proxyFactory, entry.getValue()));
			}
			try {
				entries.clear();
				entries.putAll(proxies);
				return entries;
			}
			catch (UnsupportedOperationException ex) {
				return Collections.unmodifiableMap(proxies);
			}
		}

		private Stream<?> proxyStream(AuthorizationProxyFactory proxyFactory, Stream<?> stream) {
			return stream.map(proxyFactory::proxy).onClose(stream::close);
		}

		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		private Optional<?> proxyOptional(AuthorizationProxyFactory proxyFactory, Optional<?> optional) {
			return optional.map(proxyFactory::proxy);
		}

		private Supplier<?> proxySupplier(AuthorizationProxyFactory proxyFactory, Supplier<?> supplier) {
			return () -> proxyFactory.proxy(supplier.get());
		}

	}

	private static class ReactiveTypeVisitor implements TargetVisitor {

		@Override
		@SuppressWarnings("ReactiveStreamsUnusedPublisher")
		public Object visit(AuthorizationAdvisorProxyFactory proxyFactory, Object target) {
			if (target instanceof Mono<?> mono) {
				return proxyMono(proxyFactory, mono);
			}
			if (target instanceof Flux<?> flux) {
				return proxyFlux(proxyFactory, flux);
			}
			return null;
		}

		private Mono<?> proxyMono(AuthorizationProxyFactory proxyFactory, Mono<?> mono) {
			return mono.map(proxyFactory::proxy);
		}

		private Flux<?> proxyFlux(AuthorizationProxyFactory proxyFactory, Flux<?> flux) {
			return flux.map(proxyFactory::proxy);
		}

	}

}
