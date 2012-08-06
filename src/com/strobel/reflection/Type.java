package com.strobel.reflection;

import com.strobel.core.ArrayUtilities;
import com.strobel.core.Comparer;
import com.strobel.core.StringUtilities;
import com.strobel.core.VerifyArgument;
import com.strobel.util.ContractUtils;
import com.strobel.util.EmptyArrayCache;
import com.strobel.util.TypeUtils;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import javax.lang.model.type.TypeKind;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * @author Mike Strobel
 */
@SuppressWarnings("unchecked")
public abstract class Type<T> extends MemberInfo implements java.lang.reflect.Type {

    // <editor-fold defaultstate="collapsed" desc="Constants">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONSTANTS                                                                                                          //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static final Binder DefaultBinder = new DefaultBinder();
    public static final char Delimiter = '.';
    public static final Missing Value = new Missing();
    public static final Type[] EmptyTypes = new Type[0];

    public static final Type Bottom = new BottomType();
    public static final Type NullType = new NullType();

    protected static final Object[] EmptyObjects = EmptyArrayCache.fromElementType(Object.class);
    protected static final String[] EmptyStrings = EmptyArrayCache.fromElementType(String.class);
    protected static final MethodInfo[] EmptyMethods = EmptyArrayCache.fromElementType(MethodInfo.class);
    protected static final ConstructorInfo[] EmptyConstructors = EmptyArrayCache.fromElementType(ConstructorInfo.class);
    protected static final FieldInfo[] EmptyFields = EmptyArrayCache.fromElementType(FieldInfo.class);
    protected static final MemberInfo[] EmptyMembers = EmptyArrayCache.fromElementType(MemberInfo.class);

    protected static final Set<BindingFlags> DefaultLookup = BindingFlags.PublicAll;

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Constructors">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONSTRUCTORS                                                                                                       //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    protected Type() {
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Type Information">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // REFLECTION METHODS                                                                                                 //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public MemberType getMemberType() {
        if (getDeclaringType() == null) {
            return MemberType.TypeInfo;
        }
        return MemberType.NestedType;
    }

    public boolean isNested() {
        return getDeclaringType() != null;
    }

    public boolean isVisible() {
        throw ContractUtils.unreachable();
    }

    public final boolean isClass() {
        return (getModifiers() & (Modifier.INTERFACE | ENUM_MODIFIER)) == 0;
    }

    public final boolean isInterface() {
        return Modifier.isInterface(getModifiers());
    }

    public final boolean isEnum() {
//        return isSubType(Types.Enum);
        return (getModifiers() & ENUM_MODIFIER) != 0;
    }

    public final boolean isAbstract() {
        return Modifier.isAbstract(getModifiers());
    }

    public boolean isArray() {
        return false;
    }

    public boolean isGenericType() {
        return !getTypeBindings().isEmpty();
    }

    public boolean isGenericTypeDefinition() {
        if (!isGenericType()) {
            return false;
        }

        final TypeBindings typeArguments = getTypeBindings();

        return !typeArguments.isEmpty() &&
               !typeArguments.hasBoundParameters();
    }

    public boolean isGenericParameter() {
        return false;
    }

    public boolean isPrimitive() {
        return false;
    }

    public boolean hasElementType() {
        return false;
    }

    public TypeKind getKind() {
        return TypeKind.DECLARED;
    }

    public Type getBaseType() {
        return Type.of(Object.class);
    }

    private TypeList _interfaces;

    public TypeList getInterfaces() {
        if (_interfaces == null) {
            synchronized (CACHE_LOCK) {
                if (_interfaces == null) {
                    final ArrayList<Type<?>> interfaces = getCache().getInterfaceList(MemberListType.All, null);
                    if (interfaces.isEmpty()) {
                        _interfaces = TypeList.empty();
                    }
                    else {
                        _interfaces = Type.list(interfaces);
                    }
                }
            }
        }
        return _interfaces;
    }

    protected TypeList getExplicitInterfaces() {
        return TypeList.empty();
    }

    public Class<T> getErasedClass() {
        return getCache().getErasedClass();
    }

    public T newInstance() {
        if (Helper.isReifiable(this)) {
            try {
                return getErasedClass().newInstance();
            }
            catch (Throwable t) {
                throw Error.typeInstantiationFailed(this, t);
            }
        }
        throw Error.typeCannotBeInstantiated(this);
    }

    public MethodInfo getDeclaringMethod() {
        return null;
    }

    public Type getElementType() {
        throw Error.noElementType(this);
    }

    public int getGenericParameterPosition() {
        throw Error.notGenericParameter(this);
    }

    protected TypeBindings getTypeBindings() {
        return TypeBindings.empty();
    }

    public TypeList getTypeArguments() {
        if (isGenericType()) {
            return getTypeBindings().getBoundTypes();
        }
//        throw Error.notGenericType(this);
        return TypeList.empty();
    }

    public TypeList getGenericTypeParameters() {
        if (isGenericType()) {
            return getTypeBindings().getGenericParameters();
        }
        throw Error.notGenericType(this);
    }

    public Type getGenericTypeDefinition() {
        if (isGenericType()) {
            throw ContractUtils.unreachable();
        }
        throw Error.notGenericType(this);
    }

    public boolean containsGenericParameters() {
        if (hasElementType()) {
            return getRootElementType().containsGenericParameters();
        }

        if (isGenericParameter()) {
            return true;
        }

        if (!isGenericType()) {
            return false;
        }

        final TypeBindings typeArguments = getTypeBindings();

        for (int i = 0, n = typeArguments.size(); i < n; i++) {
            if (typeArguments.getBoundType(i).containsGenericParameters()) {
                return true;
            }
        }

        return false;
    }

    public boolean isBoundedType() {
        return this.isGenericParameter() ||
               this.isWildcardType() ||
               this instanceof ICapturedType;
    }

    public boolean isUnbound() {
        return isWildcardType() &&
               getSuperBound() == Bottom &&
               getExtendsBound() == Types.Object;
    }

    public boolean isExtendsBound() {
        return isGenericParameter() ||
               isWildcardType() && getSuperBound() == Bottom;
    }

    public boolean isSuperBound() {
        return isWildcardType() &&
               (getSuperBound() != Bottom || getExtendsBound() == Types.Object);
    }

    public Type<?> getExtendsBound() {
        throw Error.notBoundedType(this);
    }

    public Type<?> getSuperBound() {
        throw Error.notWildcard(this);
    }

    public boolean isEquivalentTo(final Type<?> other) {
        if (other == this) {
            return true;
        }

        if (other == null) {
            return false;
        }

        if (other instanceof RuntimeType<?>) {
            return other.isEquivalentTo(this);
        }

        final boolean isGenericParameter = this.isGenericParameter();

        if (isGenericParameter != other.isGenericParameter()) {
            return false;
        }

        if (isGenericParameter) {
            return Comparer.equals(this.getDeclaringType(), other.getDeclaringType()) &&
                   Comparer.equals(this.getDeclaringMethod(), other.getDeclaringMethod());
        }

        final boolean isWildcard = this.isWildcardType();

        if (isWildcard != other.isWildcardType()) {
            return false;
        }

        if (isWildcard) {
            return TypeUtils.areEquivalent(getExtendsBound(), other.getExtendsBound()) &&
                   TypeUtils.areEquivalent(getSuperBound(), other.getSuperBound());
        }

        final boolean isCompound = this.isCompoundType();

        if (isCompound != other.isCompoundType()) {
            return false;
        }

        if (isCompound) {
            return TypeUtils.areEquivalent(this.getBaseType(), other.getBaseType()) &&
                   TypeUtils.areEquivalentWithOrdering(this.getExplicitInterfaces(), other.getExplicitInterfaces());
        }

        if (Comparer.equals(getErasedClass(), other.getErasedClass())) {
            if (isGenericType()) {
                return other.isGenericType() &&
                       Comparer.equals(getTypeArguments(), other.getTypeArguments());
            }
            else {
                return !other.isGenericType();
            }
        }

        return false;
    }

    @Override
    public boolean equals(final Object obj) {
        final Type<?> other;

        return obj instanceof Type<?> &&
               (other = (Type<?>)obj).isGenericParameter() == this.isGenericParameter() &&
               other.isWildcardType() == this.isWildcardType() &&
               other.isCompoundType() == this.isCompoundType() &&
               isEquivalentTo(other);
    }

    public boolean isSubType(final Type type) {
        Type current = this;

        if (current == type) {
            return false;
        }

        while (current != null && current != Type.NullType) {
            if (current.equals(type)) {
                return true;
            }
            current = current.getBaseType();
        }

        return false;
    }

    public boolean isInstance(final Object o) {
        return o != null &&
               isAssignableFrom(of(o.getClass()));
    }

    @SuppressWarnings("UnusedParameters")
    public boolean implementsInterface(final Type interfaceType) {
        Type t = this;

        while (t != null && t != Type.NullType) {
            final TypeList interfaces = t.getExplicitInterfaces();

            for (int i = 0, n = interfaces.size(); i < n; i++) {
                final Type type = interfaces.get(i);
                if (type.isEquivalentTo(interfaceType) || type.implementsInterface(interfaceType)) {
                    return true;
                }
            }

            t = t.getBaseType();
        }

        return false;
    }

    public boolean isAssignableFrom(final Type type) {
/*
        if (type == null) {
            return false;
        }

        if (type == Bottom) {
            return true;
        }

        if (TypeUtils.hasIdentityPrimitiveOrBoxingConversion(this, type)) {
            return true;
        }

        if (type.isSubType(this)) {
            return true;
        }

        if (this.isInterface()) {
            return type.implementsInterface(this);
        }
        else if (isGenericParameter()) {
            return getUpperBound().isAssignableFrom(type) &&
                   type.isAssignableFrom(getLowerBound());
        }

        return false;
*/
        return Helper.isAssignable(this, type);
    }

    public Package getPackage() {
        return getCache().getPackage();
    }

    public boolean isCompoundType() {
        return false;
    }

    public boolean isWildcardType() {
        return false;
    }

    public boolean isSynthetic() {
        return false;
    }

    public <P, R> R accept(final TypeVisitor<P, R> visitor, final P parameter) {
        return visitor.visitType(this, parameter);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Annotations">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // ANNOTATIONS                                                                                                        //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public <T extends Annotation> T getAnnotation(final Class<T> annotationClass) {
        return getErasedClass().getAnnotation(annotationClass);
    }

    @Override
    public Annotation[] getAnnotations() {
        return getErasedClass().getAnnotations();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return getErasedClass().getDeclaredAnnotations();
    }

    @Override
    public boolean isAnnotationPresent(final Class<? extends Annotation> annotationClass) {
        return getErasedClass().isAnnotationPresent(annotationClass);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Member Information">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // MEMBER INFO                                                                                                        //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public final MemberList getMember(final String name) {
        return getMember(name, DefaultLookup, EnumSet.noneOf(MemberType.class));
    }

    public final MemberList getMember(final String name, final MemberType memberType, final MemberType... memberTypes) {
        return getMember(name, DefaultLookup, EnumSet.of(memberType, memberTypes));
    }

    public MemberList getMember(final String name, final Set<BindingFlags> bindingFlags, final Set<MemberType> memberTypes) {
        VerifyArgument.notNull(name, "name");

        if (memberTypes == null || memberTypes.isEmpty()) {
            return MemberList.empty();
        }

        MethodInfo[] methods = EmptyMethods;
        ConstructorInfo[] constructors = EmptyConstructors;
        FieldInfo[] fields = EmptyFields;
        Type[] nestedTypes = EmptyTypes;

        if (memberTypes.contains(MemberType.Field)) {
            fields = getFieldCandidates(name, bindingFlags, true);
        }

        if (memberTypes.contains(MemberType.Method)) {
            methods = getMethodBaseCandidates(
                MemberType.Method,
                name,
                bindingFlags,
                CallingConvention.Any,
                null,
                true
            );
        }

        if (memberTypes.contains(MemberType.Constructor)) {
            constructors = getMethodBaseCandidates(
                MemberType.Constructor,
                name,
                bindingFlags,
                CallingConvention.Any,
                null,
                true
            );
        }

        if (memberTypes.contains(MemberType.NestedType)) {
            nestedTypes = getNestedTypeCandidates(name, bindingFlags, true);
        }

        if (memberTypes.size() == 1) {
            final MemberType memberType = memberTypes.iterator().next();
            switch (memberType) {
                case Constructor:
                    if (constructors.length == 0) {
                        return ConstructorList.empty();
                    }
                    return new ConstructorList(constructors);

                case Field:
                    if (fields.length == 0) {
                        return FieldList.empty();
                    }
                    return new FieldList(fields);

                case Method:
                    if (methods.length == 0) {
                        return MethodList.empty();
                    }
                    return new MethodList(methods);

                case NestedType:
                    if (nestedTypes.length == 0) {
                        return TypeList.empty();
                    }
                    return new TypeList(nestedTypes);
            }
        }

        final ArrayList<MemberInfo> results = new ArrayList<>(
            fields.length +
            methods.length +
            constructors.length +
            nestedTypes.length
        );

        Collections.addAll(results, fields);
        Collections.addAll(results, methods);
        Collections.addAll(results, constructors);
        Collections.addAll(results, nestedTypes);

        final MemberInfo[] array = new MemberInfo[results.size()];

        results.toArray(array);

        return new MemberList<>(MemberInfo.class, array);
    }

    public final FieldInfo getField(final String name) {
        return getField(name, DefaultLookup);
    }

    public FieldInfo getField(final String name, final Set<BindingFlags> bindingFlags) {

        final FieldInfo[] candidates = getFieldCandidates(
            name,
            bindingFlags,
            false
        );

        if (candidates.length == 0) {
            return null;
        }

        FieldInfo match = null;
        boolean multipleStaticFieldMatches = false;

        //noinspection ForLoopReplaceableByForEach
        for (int i = 0, n = candidates.length; i < n; i++) {
            final FieldInfo candidate = candidates[i];
            final Type candidateDeclaringType = candidate.getDeclaringType();

            if (match != null) {
                final Type matchDeclaringType = match.getDeclaringType();

                if (candidateDeclaringType == matchDeclaringType) {
                    throw Error.ambiguousMatch();
                }

                if (matchDeclaringType.isInterface() && candidateDeclaringType.isInterface()) {
                    multipleStaticFieldMatches = true;
                }
            }

            if (match == null || candidateDeclaringType.isSubType(match.getDeclaringType()) || match.getDeclaringType().isInterface()) {
                match = candidate;
            }
        }

        if (multipleStaticFieldMatches && match.getDeclaringType().isInterface()) {
            throw Error.ambiguousMatch();
        }

        return match;
    }

    public final MethodInfo getMethod(final String name, final Type... parameterTypes) {
        return getMethod(name, DefaultLookup, parameterTypes);
    }

    public final MethodInfo getMethod(final String name, final Set<BindingFlags> bindingFlags, final Type... parameterTypes) {
        return getMethod(name, bindingFlags, CallingConvention.Any, parameterTypes);
    }

    public MethodInfo getMethod(
        final String name,
        final Set<BindingFlags> bindingFlags,
        final CallingConvention callingConvention,
        final Type... parameterTypes) {

        final MethodInfo[] candidates = getMethodBaseCandidates(
            MemberType.Method,
            name,
            bindingFlags,
            callingConvention,
            parameterTypes,
            false
        );

        if (candidates.length == 0) {
            return null;
        }

        if (parameterTypes == null || parameterTypes.length == 0) {
            if (candidates.length == 1) {
                return candidates[0];
            }
            else if (parameterTypes == null) {
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0, n = candidates.length; i < n; i++) {
                    final MethodInfo method = candidates[i];
                    if (!Binder.compareMethodSignatureAndName(method, candidates[0])) {
                        throw Error.ambiguousMatch();
                    }
                }

                // All the methods have the exact same name and sig so return the most derived one.
                return (MethodInfo)Binder.findMostDerivedNewSlotMethod(candidates, candidates.length);
            }
        }

        return (MethodInfo)DefaultBinder.selectMethod(bindingFlags, candidates, parameterTypes);
    }

    public final ConstructorInfo getConstructor(final Type... parameterTypes) {
        return getConstructor(DefaultLookup, parameterTypes);
    }

    public final ConstructorInfo getConstructor(final Set<BindingFlags> bindingFlags, final Type... parameterTypes) {
        return getConstructor(bindingFlags, CallingConvention.Any, parameterTypes);
    }

    public ConstructorInfo getConstructor(
        final Set<BindingFlags> bindingFlags,
        final CallingConvention callingConvention,
        final Type... parameterTypes) {

        final ConstructorInfo[] candidates = getMethodBaseCandidates(
            MemberType.Constructor,
            null,
            bindingFlags,
            callingConvention,
            parameterTypes,
            false
        );

        if (candidates.length == 0) {
            return null;
        }

        if (parameterTypes == null || parameterTypes.length == 0) {
            if (candidates.length == 1) {
                return candidates[0];
            }
            else if (parameterTypes == null) {
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0, n = candidates.length; i < n; i++) {
                    final ConstructorInfo constructor = candidates[i];
                    if (!Binder.compareMethodSignatureAndName(constructor, candidates[0])) {
                        throw Error.ambiguousMatch();
                    }
                }

                // All the methods have the exact same name and sig so return the most derived one.
                return (ConstructorInfo)Binder.findMostDerivedNewSlotMethod(candidates, candidates.length);
            }
        }

        return (ConstructorInfo)DefaultBinder.selectMethod(bindingFlags, candidates, parameterTypes);
    }

    public final MemberList getMembers() {
        return getMembers(DefaultLookup, EnumSet.allOf(MemberType.class));
    }

    public final MemberList getMembersOfType(final Set<MemberType> memberTypes) {
        return getMembers(DefaultLookup, memberTypes);
    }

    public final MemberList getMembers(final MemberType memberType, final MemberType... memberTypes) {
        return getMembers(DefaultLookup, EnumSet.of(memberType, memberTypes));
    }

    public final MemberList getMembers(final Set<BindingFlags> bindingFlags) {
        return getMembers(bindingFlags, EnumSet.allOf(MemberType.class));
    }

    public final MemberList getMembers(final Set<BindingFlags> bindingFlags, final MemberType memberType, final MemberType... memberTypes) {
        return getMembers(bindingFlags, EnumSet.of(memberType, memberTypes));
    }

    public MemberList getMembers(final Set<BindingFlags> bindingFlags, final Set<MemberType> memberTypes) {
        MethodInfo[] methods = EmptyMethods;
        ConstructorInfo[] constructors = EmptyConstructors;
        FieldInfo[] fields = EmptyFields;
        Type[] nestedTypes = EmptyTypes;

        if (memberTypes.contains(MemberType.Field)) {
            fields = getFieldCandidates(null, bindingFlags, false);
        }

        if (memberTypes.contains(MemberType.Method)) {
            methods = getMethodBaseCandidates(
                MemberType.Method,
                null,
                bindingFlags,
                CallingConvention.Any,
                null,
                false
            );
        }

        if (memberTypes.contains(MemberType.Constructor)) {
            constructors = getMethodBaseCandidates(
                MemberType.Constructor,
                null,
                bindingFlags,
                CallingConvention.Any,
                null,
                false
            );
        }

        if (memberTypes.contains(MemberType.NestedType)) {
            nestedTypes = getNestedTypeCandidates(null, bindingFlags, false);
        }

        if (memberTypes.size() == 1) {
            final MemberType memberType = memberTypes.iterator().next();
            switch (memberType) {
                case Constructor:
                    if (constructors.length == 0) {
                        return ConstructorList.empty();
                    }
                    return new ConstructorList(constructors);

                case Field:
                    if (fields.length == 0) {
                        return FieldList.empty();
                    }
                    return new FieldList(fields);

                case Method:
                    if (methods.length == 0) {
                        return MethodList.empty();
                    }
                    return new MethodList(methods);

                case NestedType:
                    if (nestedTypes.length == 0) {
                        return TypeList.empty();
                    }
                    return new TypeList(nestedTypes);
            }
        }

        final ArrayList<MemberInfo> results = new ArrayList<>(
            fields.length +
            methods.length +
            constructors.length +
            nestedTypes.length
        );

        Collections.addAll(results, fields);
        Collections.addAll(results, methods);
        Collections.addAll(results, constructors);
        Collections.addAll(results, nestedTypes);

        final MemberInfo[] array = new MemberInfo[results.size()];

        results.toArray(array);

        return new MemberList<>(MemberInfo.class, array);
    }

    public final FieldList getFields() {
        return getFields(DefaultLookup);
    }

    public FieldList getFields(final Set<BindingFlags> bindingFlags) {
        final FieldInfo[] candidates = getFieldCandidates(null, bindingFlags, false);

        if (candidates == null || candidates.length == 0) {
            return FieldList.empty();
        }

        return new FieldList(candidates);
    }

    public final MethodList getMethods() {
        return getMethods(DefaultLookup, CallingConvention.Any);
    }

    public final MethodList getMethods(final Set<BindingFlags> bindingFlags) {
        return getMethods(bindingFlags, CallingConvention.Any);
    }

    public MethodList getMethods(final Set<BindingFlags> bindingFlags, final CallingConvention callingConvention) {
        final MethodInfo[] candidates = getMethodBaseCandidates(
            MemberType.Method,
            null,
            bindingFlags,
            callingConvention,
            null,
            false
        );

        if (candidates == null || candidates.length == 0) {
            return MethodList.empty();
        }

        return new MethodList(candidates);
    }

    public final ConstructorList getConstructors() {
        return getConstructors(DefaultLookup);
    }

    public ConstructorList getConstructors(final Set<BindingFlags> bindingFlags) {
        final ConstructorInfo[] candidates = getMethodBaseCandidates(
            MemberType.Constructor,
            null,
            bindingFlags,
            CallingConvention.Any,
            null,
            false
        );

        if (candidates == null || candidates.length == 0) {
            return ConstructorList.empty();
        }

        return new ConstructorList(candidates);
    }

    public final TypeList getNestedTypes() {
        return getNestedTypes(DefaultLookup);
    }

    public TypeList getNestedTypes(final Set<BindingFlags> bindingFlags) {
        final Type[] candidates = getNestedTypeCandidates(null, bindingFlags, false);

        if (ArrayUtilities.isNullOrEmpty(candidates)) {
            return TypeList.empty();
        }

        return list(candidates);
    }

    public final Type<?> getNestedType(final String fullName) {
        return getNestedType(fullName, DefaultLookup);
    }

    public Type<?> getNestedType(final String fullName, final Set<BindingFlags> bindingFlags) {
        VerifyArgument.notNull(fullName, "fullName");

        final String name;

        if (fullName != null) {
            final String ownerName = getFullName();

            final boolean isLongName = bindingFlags.contains(BindingFlags.IgnoreCase)
                                       ? StringUtilities.startsWithIgnoreCase(fullName, ownerName)
                                       : fullName.startsWith(ownerName);
            if (isLongName) {
                if (fullName.length() == ownerName.length()) {
                    return null;
                }
                name = fullName.substring(ownerName.length() + 1);
            }
            else {
                name = fullName;
            }
        }
        else {
            name = null;
        }

        final FilterOptions filterOptions = getFilterOptions(name, bindingFlags, false);
        final ArrayList<Type<?>> nestedTypes = getCache().getNestedTypeList(filterOptions.listOptions, name);
        final Set<BindingFlags> flags = EnumSet.copyOf(bindingFlags);

        if (!flags.remove(BindingFlags.Static)) {
            flags.add(BindingFlags.Static);
        }

        Type<?> match = null;

        for (int i = 0, n = nestedTypes.size(); i < n; i++) {
            final Type<?> nestedType = nestedTypes.get(i);
            if (filterApplyType(nestedType, flags, name, filterOptions.prefixLookup)) {
                if (match != null) {
                    throw Error.ambiguousMatch();
                }
                match = nestedType;
            }
        }

        return match;
    }

    public Object[] getEnumConstants() {
        throw Error.notEnumType(this);
    }

    public String[] getEnumNames() {
        throw Error.notEnumType(this);
    }

    @Override
    public int hashCode() {
        return Helper.hashCode(this);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Transformation Methods">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // TRANSFORMATION METHODS                                                                                             //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public Type<T[]> makeArrayType() {
        synchronized (CACHE_LOCK) {
            return CACHE.getArrayType(this);
        }
    }

    public final Type<T> makeGenericType(final TypeList typeArguments) {
        VerifyArgument.noNullElements(typeArguments, "typeArguments");
        return makeGenericTypeCore(typeArguments);
    }

    public final Type<T> makeGenericType(final Type<?>... typeArguments) {
        return makeGenericTypeCore(
            list(
                VerifyArgument.noNullElements(typeArguments, "typeArguments")
            )
        );
    }

    private ErasedType<T> _erasedType;

    public final Type<T> getErasedType() {
        if (!isGenericType()) {
            return this;
        }

        if (!isGenericTypeDefinition()) {
            return getGenericTypeDefinition().getErasedType();
        }

        if (_erasedType == null) {
            synchronized (CACHE_LOCK) {
                //noinspection ConstantConditions
                if (_erasedType == null) {
                    _erasedType = new ErasedType<>(this);
                }
            }
        }
        return _erasedType;
    }

    @SuppressWarnings("UnusedParameters")
    protected Type makeGenericTypeCore(final TypeList typeArguments) {
        throw Error.notGenericType(this);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Internal Methods">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // INTERNAL METHODS                                                                                                   //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    Type getRootElementType() {
        Type rootElementType = this;

        while (rootElementType.hasElementType()) {
            rootElementType = rootElementType.getElementType();
        }

        return rootElementType;
    }

    Type getMostSpecificType(final Type t1, final Type t2) {
        if (t1.isSubType(t2)) {
            return t1;
        }
        if (t2.isSubType(t1)) {
            return t2;
        }
        return null;
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Name and Signature Formatting">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NAME AND SIGNATURE FORMATTING                                                                                      //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return getBriefDescription();
    }

    @Override
    public String getName() {
        return getCache().getName();
    }

    protected String getClassFullName() {
        return getErasedClass().getName();
    }

    protected String getClassSimpleName() {
        return getErasedClass().getSimpleName();
    }

    public String getFullName() {
        return getCache().getFullName();
    }

    public String getInternalName() {
        return getCache().getInternalName();
    }

    /**
     * Method that returns full generic signature of the type; suitable
     * as signature for things like ASM package.
     */
    public String getSignature() {
        return getCache().getSignature();
    }

    /**
     * Method that returns full generic signature of the type; suitable
     * as signature for things like ASM package.
     */
    public String getGenericSignature() {
        return getCache().getGenericSignature();
    }

    /**
     * Method that returns type erased signature of the type; suitable
     * as non-generic signature some packages need
     */
    public String getErasedSignature() {
        return getCache().getErasedSignature();
    }

    /**
     * Human-readable full description of type, which includes specification
     * of super types (in brief format)
     */
    public String getFullDescription() {
        return getCache().getFullDescription();
    }

    /**
     * Human-readable simple description, which does not include
     * information about super types and uses simple type names instead
     * of package-qualified names.
     */
    public String getSimpleDescription() {
        return getCache().getSimpleDescription();
    }

    /**
     * Human-readable brief description of type, which does not include
     * information about super types.
     */
    public String getBriefDescription() {
        return getCache().getBriefDescription();
    }

    /**
     * Human-readable erased description of type.
     */
    public String getErasedDescription() {
        return getCache().getErasedDescription();
    }

    public StringBuilder appendBriefDescription(final StringBuilder sb) {
        StringBuilder s = _appendClassName(sb, true, true);

        if (isGenericType()) {
            final TypeList typeArguments = getTypeBindings().getBoundTypes();
            final int count = typeArguments.size();
            if (count > 0) {
                s.append('<');
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < count; ++i) {
                    if (i != 0) {
                        s.append(", ");
                    }
                    s = typeArguments.get(i).appendBriefDescription(s);
                }
                s.append('>');
            }
        }

        return s;
    }

    public StringBuilder appendSimpleDescription(final StringBuilder sb) {
        StringBuilder s = _appendClassName(sb, false, false);

        if (isGenericType()) {
            final TypeList typeArguments = getTypeBindings().getBoundTypes();
            final int count = typeArguments.size();
            if (count > 0) {
                s.append('<');
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < count; ++i) {
                    if (i != 0) {
                        s.append(", ");
                    }
                    s = typeArguments.get(i).appendSimpleDescription(s);
                }
                s.append('>');
            }
        }

        return s;
    }

    public StringBuilder appendErasedDescription(final StringBuilder sb) {
        return sb.append(getClassFullName());
    }

    public StringBuilder appendFullDescription(final StringBuilder sb) {
        StringBuilder s = _appendClassName(sb, false, false);

        if (isGenericType()) {
            final TypeList typeArguments = getTypeBindings().getBoundTypes();
            final int count = typeArguments.size();
            if (count > 0) {
                s.append('<');
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < count; ++i) {
                    if (i != 0) {
                        s.append(", ");
                    }
                    s = typeArguments.get(i).appendBriefDescription(s);
                }
                s.append('>');
            }
        }

        final Type baseType = getBaseType();

        if (baseType != null && baseType != Types.Object) {
            s.append(" extends ");
            s = baseType.appendBriefDescription(s);
        }

        final TypeList interfaces = getExplicitInterfaces();
        final int interfaceCount = interfaces.size();

        if (interfaceCount > 0) {
            s.append(" implements ");
            for (int i = 0; i < interfaceCount; ++i) {
                if (i != 0) {
                    s.append(",");
                }
                s = interfaces.get(i).appendBriefDescription(s);
            }
        }

        return s;
    }

    public StringBuilder appendSignature(final StringBuilder sb) {
        if (isGenericParameter()) {
            sb.append('T');
            sb.append(getName());
            sb.append(';');
            return sb;
        }

        return _appendClassSignature(sb);
    }

    public StringBuilder appendGenericSignature(final StringBuilder sb) {
        StringBuilder s = sb;

        if (isGenericParameter()) {
            final Type<?> extendsBound = getExtendsBound();

            s.append(getName());

            if (extendsBound.isInterface()) {
                s.append(':');
            }

            s.append(':');
            s = extendsBound.appendSignature(s);

            return s;
        }

        if (isGenericType()) {
            final TypeList genericParameters = getTypeBindings().getBoundTypes();
            final int count = genericParameters.size();

            if (count > 0) {
                s.append('<');
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < count; ++i) {
                    s = genericParameters.get(i).appendGenericSignature(s);
                }
                s.append('>');
            }
        }

        final Type baseType = getBaseType();
        final TypeList interfaces = getInterfaces();

        if (baseType == null) {
            if (interfaces.isEmpty()) {
                s = Types.Object.appendSignature(s);
            }
        }
        else {
            s = baseType.appendSignature(s);
        }

        for (final Type interfaceType : interfaces) {
            s = interfaceType.appendSignature(s);
        }

        return s;
    }

    public StringBuilder appendErasedSignature(final StringBuilder sb) {
        if (isGenericType() && !isGenericTypeDefinition()) {
            return getGenericTypeDefinition().appendErasedSignature(sb);
        }
        return _appendErasedClassSignature(sb);
    }

    protected StringBuilder _appendClassSignature(final StringBuilder sb) {
        StringBuilder s = sb;

        s.append('L');
        s = _appendClassName(s, true, false);

        if (isGenericType()) {
            final TypeList genericParameters = getTypeBindings().getBoundTypes();
            final int count = genericParameters.size();

            if (count > 0) {
                s.append('<');
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < count; ++i) {
                    s = genericParameters.get(i).appendSignature(s);
                }
                s.append('>');
            }
        }

        s.append(';');
        return s;
    }

    protected StringBuilder _appendErasedClassSignature(StringBuilder sb) {
        sb.append('L');
        sb = _appendClassName(sb, true, false);
        sb.append(';');
        return sb;
    }

    protected StringBuilder _appendClassDescription(final StringBuilder sb) {
        StringBuilder s = sb;

        s.append(getClassFullName());

        if (isGenericType()) {
            final TypeList typeArguments = getTypeBindings().getBoundTypes();
            final int count = typeArguments.size();
            if (count > 0) {
                s.append('<');
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < count; ++i) {
                    s = typeArguments.get(i)._appendErasedClassSignature(s);
                }
                s.append('>');
            }
        }

        return s;
    }

    protected StringBuilder _appendClassName(final StringBuilder sb, final boolean fullName, final boolean dottedName) {
        if (!fullName) {
            return sb.append(getClassSimpleName());
        }

        final String name = getClassFullName();

        if (dottedName) {
            return sb.append(name);
        }

        final int start;
        final int packageEnd = name.lastIndexOf('.');

        if (packageEnd >= 0) {
            for (int i = 0; i < packageEnd; i++) {
                char c = name.charAt(i);
                if (c == '.') {
                    c = '/';
                }
                sb.append(c);
            }
            sb.append('/');
            start = packageEnd + 1;
        }
        else {
            start = 0;
        }

        return sb.append(name, start, name.length());
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Type Cache and Factory Methods">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // REFLECTED TYPE CACHE                                                                                               //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    final static Object CACHE_LOCK = new Object();
    final static JavacFileManager FILE_MANAGER;
    final static JavaCompiler COMPILER;
    final static TypeCache CACHE;
    final static Context CONTEXT;
    final static NewResolver RESOLVER;
    final static Type<?>[] PRIMITIVE_TYPES;

    static {
        synchronized (CACHE_LOCK) {
            CACHE = new TypeCache();

            final Context context = new Context();

            FILE_MANAGER = new JavacFileManager(context, true, Charset.defaultCharset());

            com.sun.tools.javac.code.Types.instance(context);
            Resolve.instance(context);
            Names.instance(context);

            COMPILER = JavaCompiler.instance(context);
            CONTEXT = context;
            RESOLVER = new NewResolver();
            PRIMITIVE_TYPES = new PrimitiveType<?>[TypeKind.values().length];

            PrimitiveTypes.ensureRegistered();

            PRIMITIVE_TYPES[TypeKind.VOID.ordinal()] = PrimitiveTypes.Void;
            PRIMITIVE_TYPES[TypeKind.BOOLEAN.ordinal()] = PrimitiveTypes.Boolean;
            PRIMITIVE_TYPES[TypeKind.BYTE.ordinal()] = PrimitiveTypes.Byte;
            PRIMITIVE_TYPES[TypeKind.CHAR.ordinal()] = PrimitiveTypes.Character;
            PRIMITIVE_TYPES[TypeKind.SHORT.ordinal()] = PrimitiveTypes.Short;
            PRIMITIVE_TYPES[TypeKind.INT.ordinal()] = PrimitiveTypes.Integer;
            PRIMITIVE_TYPES[TypeKind.LONG.ordinal()] = PrimitiveTypes.Long;
            PRIMITIVE_TYPES[TypeKind.FLOAT.ordinal()] = PrimitiveTypes.Float;
            PRIMITIVE_TYPES[TypeKind.DOUBLE.ordinal()] = PrimitiveTypes.Double;

            Types.ensureRegistered();
        }
    }

    public static <T> Type<T> of(final Class<T> clazz) {
        synchronized (CACHE_LOCK) {
            final Type<T> reflectedType = CACHE.find(clazz);

            if (reflectedType != null) {
                return reflectedType;
            }

//            loadAncestors(clazz);

            final Type<T> resolvedType = (Type<T>)RESOLVER.resolve(clazz);

            if (resolvedType != null) {
                return resolvedType;
            }

            throw Error.couldNotResolveType(clazz);
        }
    }

    public static <T> Type<T> getType(final T object) {
        if (object == null) {
            return Type.NullType;
        }

        return (Type<T>)Type.of(object.getClass());
    }

    private static void loadAncestors(final java.lang.reflect.Type type) {
        if (type instanceof TypeVariable) {
            return;
        }

        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType)type;

            loadAncestors(parameterizedType.getRawType());

            for (final java.lang.reflect.Type typeArgument : parameterizedType.getActualTypeArguments()) {
                loadAncestors(typeArgument);
            }

            return;
        }

        if (type instanceof Class<?>) {
            final Class<?> classType = (Class) type;

            java.lang.reflect.Type superclass = classType.getGenericSuperclass();

            if (superclass == null) {
                superclass = classType.getSuperclass();
            }

            if (superclass != null && superclass != Object.class && superclass != classType) {
                of(superclass);
            }

            java.lang.reflect.Type[] interfaces = classType.getGenericInterfaces();

            if (interfaces == null) {
                interfaces = classType.getInterfaces();
            }

            for (final java.lang.reflect.Type interfaceType : interfaces) {
                of(interfaceType);
            }
        }
    }

/*
    static <T> Type<T> of(final com.sun.tools.javac.code.Type type) {
        if (type instanceof com.sun.tools.javac.code.Type.ArrayType) {
            return (Type<T>)of(((com.sun.tools.javac.code.Type.ArrayType)type).getComponentType()).makeArrayType();
        }

        final TypeKind typeKind = type.getKind();

        if (typeKind == TypeKind.VOID || typeKind.isPrimitive()) {
            return (Type<T>)PRIMITIVE_TYPES[typeKind.ordinal()];
        }

        synchronized (CACHE_LOCK) {
            Type<T> resultType = (Type<T>)tryFind(type);

            if (resultType != null) {
                return resultType;
            }

            loadAncestors((Symbol.ClassSymbol)type.asElement());

            resultType = (Type<T>)RESOLVER.visit(type.asElement(), null);

            if (resultType != null) {
                return resultType;
            }

            throw Error.couldNotResolveType(type);
        }
    }
*/
    static <T> Type<T> of(final java.lang.reflect.Type type) {
        if (type instanceof GenericArrayType) {
            return (Type<T>)of(((GenericArrayType)type).getGenericComponentType()).makeArrayType();
        }

        if (type instanceof Class<?>) {
            return of((Class<T>) type);
        }

        synchronized (CACHE_LOCK) {
            Type<T> resultType = (Type<T>)tryFind(type);

            if (resultType != null) {
                return resultType;
            }

//            loadAncestors(type);

            resultType = (Type<T>)RESOLVER.resolve(type);

            if (resultType != null) {
                return resultType;
            }

            throw Error.couldNotResolveType(type);
        }
    }

    static Type<?> tryFind(final com.sun.tools.javac.code.Type type) {
        final TypeKind typeKind = type.getKind();

        if (typeKind == TypeKind.VOID || typeKind.isPrimitive()) {
            return PRIMITIVE_TYPES[typeKind.ordinal()];
        }

        if (typeKind != TypeKind.DECLARED) {
            return null;
        }

        synchronized (CACHE_LOCK) {
            final Class<?> clazz;
            final String className = type.asElement().flatName().toString();

            try {
                clazz = Class.forName(className);
            }
            catch (ClassNotFoundException e) {
                throw Error.couldNotResolveType(className);
            }

            return CACHE.find(clazz);
        }
    }

    static Type<?> tryFind(final java.lang.reflect.Type type) {
        if (type instanceof Class<?>) {
            final Class<?> classType = (Class<?>)type;

            if (classType.isPrimitive() || classType == Void.class) {
                return of(classType);
            }

            synchronized (CACHE_LOCK) {
                return CACHE.find(classType);
            }
        }

        return null;
    }

    public static <T> Type<? extends T> makeExtendsWildcard(final Type<T> bound) {
        return new WildcardType<>(
            VerifyArgument.notNull(bound, "bound"),
            Bottom
        );
    }

    public static <T> Type<? super T> makeSuperWildcard(final Type<T> bound) {
        return new WildcardType<>(
            Types.Object,
            bound
        );
    }

    public static WildcardType<?> makeWildcard() {
        return new WildcardType<>(
            Types.Object,
            Bottom
        );
    }

    public static CompoundType<?> makeCompoundType(final TypeList bounds) {
        VerifyArgument.notEmpty(bounds, "bounds");
        VerifyArgument.noNullElements(bounds, "bounds");

        final Type<?> baseType;
        final TypeList interfaces;

        if (!bounds.get(0).isInterface()) {
            baseType = bounds.get(0);
            interfaces = bounds.subList(1, bounds.size());
        }
        else {
            baseType = Types.Object;
            interfaces = bounds;
        }

        return makeCompoundType(baseType, interfaces);
    }

    public static CompoundType<?> makeCompoundType(final Type<?> baseType, final TypeList interfaces) {
        VerifyArgument.notNull(baseType, "baseType");
        VerifyArgument.noNullElements(interfaces, "interfaces");

        return makeCompoundTypeCore(baseType, interfaces);
    }

    private static <T> CompoundType<T> makeCompoundTypeCore(final Type<T> baseType, final TypeList interfaces) {
        if (baseType.isGenericParameter()) {
            throw Error.compoundTypeMayNotHaveGenericParameterBound();
        }

        for (int i = 0, n = interfaces.size(); i < n; i++) {
            final Type type = interfaces.get(i);

            if (type.isGenericParameter()) {
                throw Error.compoundTypeMayNotHaveGenericParameterBound();
            }

            if (!type.isInterface()) {
                throw Error.compoundTypeMayOnlyHaveOneClassBound();
            }
        }

        return new CompoundType<>(interfaces, baseType);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="List Factory Methods">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // LIST FACTORY METHODS                                                                                               //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static TypeList list(final Class<?>... classes) {
        if (ArrayUtilities.isNullOrEmpty(classes)) {
            return TypeList.empty();
        }

        final Type<?>[] types = new Type<?>[classes.length];

        for (int i = 0, n = classes.length; i < n; i++) {
            types[i] = of(classes[i]);
        }

        return new TypeList(types);
    }

    public static TypeList list(final Type... types) {
        return new TypeList(types);
    }

    public static TypeList list(final List<? extends Type> types) {
        return new TypeList(types);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Member Resolution and Filtering">

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // TYPE HIERARCHY AND MEMBER RESOLUTION INFO                                                                          //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private RuntimeTypeCache<T> _cache;

    final RuntimeTypeCache<T> getCache() {
        if (_cache == null) {
            synchronized (CACHE_LOCK) {
                if (_cache == null) {
                    _cache = new RuntimeTypeCache<>(this);
                }
            }
        }
        return _cache;
    }

    protected ConstructorList getDeclaredConstructors() {
        return ConstructorList.empty();
    }

    protected MethodList getDeclaredMethods() {
        return MethodList.empty();
    }

    protected FieldList getDeclaredFields() {
        return FieldList.empty();
    }

    protected TypeList getDeclaredTypes() {
        return TypeList.empty();
    }

    @SuppressWarnings("unchecked")
    private <T extends MethodBase> T[] getMethodBaseCandidates(
        final MemberType type,
        final String name,
        final Set<BindingFlags> bindingFlags,
        final CallingConvention callingConvention,
        final Type[] parameterTypes,
        final boolean allowPrefixLookup) {

        final FilterOptions filterOptions = getFilterOptions(name, bindingFlags, allowPrefixLookup);

        final ArrayList<T> source;

        if (type == MemberType.Constructor) {
            source = (ArrayList<T>)getCache().getConstructorList(filterOptions.listOptions, name);
        }
        else {
            source = (ArrayList<T>)getCache().getMethodList(filterOptions.listOptions, name);
        }

        final Set<BindingFlags> flags = EnumSet.copyOf(bindingFlags);

        if (!flags.remove(BindingFlags.DeclaredOnly)) {
            flags.add(BindingFlags.DeclaredOnly);
        }

        List<MethodBase> candidates = null;

        //noinspection ForLoopReplaceableByForEach
        for (int i = 0, n = source.size(); i < n; i++) {
            final MethodBase method = source.get(i);
            final Set<BindingFlags> methodFlags;

            if (type == MemberType.Constructor) {
                methodFlags = ((RuntimeConstructorInfo)method).getBindingFlags();
            }
            else {
                methodFlags = ((RuntimeMethodInfo)method).getBindingFlags();
            }

            final boolean passesFilter = filterMethodBase(
                method,
                methodFlags,
                flags,
                callingConvention,
                parameterTypes
            );

            if (!passesFilter) {
                continue;
            }

            if (filterOptions.prefixLookup) {
                if (!filterApplyPrefixLookup(method, filterOptions.name, filterOptions.ignoreCase)) {
                    continue;
                }
            }
            else if (name != null) {
                final String methodName = method.getName();
                if (filterOptions.ignoreCase ? !name.equalsIgnoreCase(methodName) : !name.equals(methodName)) {
                    continue;
                }
            }

            if (candidates == null) {
                candidates = new ArrayList<>(n);
            }

            candidates.add(method);
        }

        if (candidates == null) {
            if (type == MemberType.Constructor) {
                return (T[])EmptyConstructors;
            }
            return (T[])EmptyMethods;
        }

        final T[] results = (T[])Array.newInstance(
            type == MemberType.Constructor ? ConstructorInfo.class : MethodInfo.class,
            candidates.size()
        );

        candidates.toArray((Object[])results);

        return results;
    }

    @SuppressWarnings("unchecked")
    private FieldInfo[] getFieldCandidates(
        final String name,
        final Set<BindingFlags> bindingFlags,
        final boolean allowPrefixLookup) {

        final Set<BindingFlags> flags = EnumSet.copyOf(bindingFlags);

        if (!flags.remove(BindingFlags.DeclaredOnly)) {
            flags.add(BindingFlags.DeclaredOnly);
        }

        final FilterOptions filterOptions = getFilterOptions(name, flags, allowPrefixLookup);
        final ArrayList<RuntimeFieldInfo> fields = getCache().getFieldList(filterOptions.listOptions, name);

        List<FieldInfo> candidates = null;

        //noinspection ForLoopReplaceableByForEach
        for (int i = 0, n = fields.size(); i < n; i++) {
            final FieldInfo field = fields.get(i);
            final Set<BindingFlags> fieldFlags = BindingFlags.fromMember(field);

            if (!flags.containsAll(fieldFlags)) {
                continue;
            }

            if (filterOptions.prefixLookup) {
                if (!filterApplyPrefixLookup(field, filterOptions.name, filterOptions.ignoreCase)) {
                    continue;
                }
            }
            else if (name != null) {
                final String methodName = field.getName();
                if (filterOptions.ignoreCase ? !name.equalsIgnoreCase(methodName) : !name.equals(methodName)) {
                    continue;
                }
            }

            if (candidates == null) {
                candidates = new ArrayList<>(n);
            }

            candidates.add(field);
        }

        if (candidates == null) {
            return EmptyFields;
        }

        final FieldInfo[] results = new FieldInfo[candidates.size()];

        return candidates.toArray(results);
    }

    private Type[] getNestedTypeCandidates(final String fullName, final Set<BindingFlags> bindingFlags, final boolean allowPrefixLookup) {

        final String name;

        if (fullName != null) {
            final String ownerName = getName();

            final boolean isLongName = bindingFlags.contains(BindingFlags.IgnoreCase)
                                       ? StringUtilities.startsWithIgnoreCase(fullName, ownerName)
                                       : fullName.startsWith(ownerName);
            if (isLongName) {
                if (fullName.length() == ownerName.length()) {
                    return EmptyTypes;
                }
                name = fullName.substring(ownerName.length() + 1);
            }
            else {
                name = fullName;
            }
        }
        else {
            name = null;
        }

        final FilterOptions filterOptions = getFilterOptions(name, bindingFlags, allowPrefixLookup);

        final ArrayList<Type<?>> nestedTypes = getCache().getNestedTypeList(filterOptions.listOptions, name);
        final ListBuffer<Type<?>> candidates = new ListBuffer<>();

        for (int i = 0, n = nestedTypes.size(); i < n; i++) {
            final Type<?> nestedType = nestedTypes.get(i);
            if (filterApplyType(nestedType, bindingFlags, name, filterOptions.prefixLookup)) {
                candidates.add(nestedType);
            }
        }

        if (candidates.isEmpty()) {
            return EmptyTypes;
        }

        return candidates.toArray(new Type[candidates.size()]);
    }

    boolean filterMethodBase(
        final MethodBase method,
        final Set<BindingFlags> methodFlags,
        final Set<BindingFlags> bindingFlags,
        final CallingConvention callingConventions,
        final Type... argumentTypes) {

        if (!bindingFlags.containsAll(methodFlags)) {
            return false;
        }

        if (callingConventions != null &&
            callingConventions != CallingConvention.Any &&
            callingConventions != method.getCallingConvention()) {

            return false;
        }

        if (argumentTypes != null) {
            final ParameterList parameters = method.getParameters();

            final int definedParameterCount = parameters.size();
            final int suppliedArgumentCount = argumentTypes.length;

            if (suppliedArgumentCount != definedParameterCount) {

                // If the number of supplied arguments differs than the number in the signature AND
                // we are not filtering for a dynamic call, i.e., InvokeMethod or CreateInstance,
                // then filter out the method.

                if (bindingFlags.contains(BindingFlags.InvokeMethod) || bindingFlags.contains(BindingFlags.CreateInstance)) {
                    return false;
                }

                if (method.getCallingConvention() == CallingConvention.VarArgs) {
                    if (definedParameterCount == 0) {
                        return false;
                    }

                    // If we're short by more than one argument, we can't bind to the VarArgs parameter.

                    if (suppliedArgumentCount < definedParameterCount - 1) {
                        return false;
                    }

                    final ParameterInfo lastParameter = parameters.get(definedParameterCount - 1);
                    final Type lastParameterType = lastParameter.getParameterType();

                    if (!lastParameterType.isArray()) {
                        return false;
                    }
                }
                else if (suppliedArgumentCount != 0) {
                    return false;
                }
            }
            else if (bindingFlags.contains(BindingFlags.ExactBinding) && !bindingFlags.contains(BindingFlags.InvokeMethod)) {
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < definedParameterCount; i++) {
                    if (!TypeUtils.areEquivalent(parameters.get(i).getParameterType(), argumentTypes[i])) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private boolean filterApplyType(
        final Type<?> type,
        final Set<BindingFlags> bindingFlags,
        final String name,
        final boolean prefixLookup) {
        VerifyArgument.notNull(type, "type");

        final boolean isPublic = type.isPublic();
        final boolean isStatic = type.isStatic();

        return filterApplyCore(
            type,
            bindingFlags,
            isPublic,
            type.isNested() && type.isPackagePrivate(),
            isStatic,
            name,
            prefixLookup
        );
    }

    private boolean filterApplyCore(
        final MemberInfo member,
        final Set<BindingFlags> bindingFlags,
        final boolean isPublic,
        final boolean isPackagePrivate,
        final boolean isStatic,
        final String name,
        final boolean prefixLookup) {

        if (isPublic) {
            if (!bindingFlags.contains(BindingFlags.Public)) {
                return false;
            }
        }
        else {
            if (!bindingFlags.contains(BindingFlags.NonPublic)) {
                return false;
            }
        }

        final Type<?> thisType = isGenericType() ? getGenericTypeDefinition() : this;
        final boolean isInherited = member.getDeclaringType() != thisType;

        if (isInherited && bindingFlags.contains(BindingFlags.DeclaredOnly)) {
            return false;
        }

        if (member.getMemberType() != MemberType.TypeInfo &&
            member.getMemberType() != MemberType.NestedType) {
            if (isStatic) {
                if (!bindingFlags.contains(BindingFlags.FlattenHierarchy) && isInherited) {
                    return false;
                }

                if (!bindingFlags.contains(BindingFlags.Static)) {
                    return false;
                }
            }
            else {
                if (!bindingFlags.contains(BindingFlags.Instance)) {
                    return false;
                }
            }
        }

        if (prefixLookup) {
            if (!filterApplyPrefixLookup(member, name, bindingFlags.contains(BindingFlags.IgnoreCase))) {
                return false;
            }
        }

        /*
            Asymmetry:

             Package-private, inherited, instance, non-protected, non-virtual, non-abstract members returned iff
             BindingFlags !DeclaredOnly, Instance and Public are present except for fields
        */

        //noinspection SimplifiableIfStatement
        if (!bindingFlags.contains(BindingFlags.DeclaredOnly) &&    // DeclaredOnly not present
            isInherited &&                                          // Is inherited Member

            isPackagePrivate &&                                     // Is package-private member
            bindingFlags.contains(BindingFlags.NonPublic) &&        // BindingFlag.NonPublic present

            !isStatic &&                                            // Is instance member
            bindingFlags.contains(BindingFlags.Instance))           // BindingFlag.Instance present
        {
            return member instanceof MethodInfo &&
                   !member.isFinal();
        }

        return true;
    }

    private boolean filterApplyPrefixLookup(final MemberInfo method, final String name, final boolean ignoreCase) {
        final String methodName = method.getName();
        if (ignoreCase) {
            if (!StringUtilities.startsWithIgnoreCase(methodName.toLowerCase(), name)) {
                return false;
            }
        }
        else {
            if (!methodName.toLowerCase().startsWith(name)) {
                return false;
            }
        }
        return true;
    }

    private static FilterOptions getFilterOptions(final String name, final Set<BindingFlags> bindingFlags, final boolean allowPrefixLookup) {
        String filterName = name;
        boolean prefixLookup = false;
        boolean ignoreCase = false;
        MemberListType listOptions = MemberListType.All;

        if (name != null) {
            if (bindingFlags.contains(BindingFlags.IgnoreCase)) {
                filterName = name.toLowerCase();
                ignoreCase = true;
                listOptions = MemberListType.CaseInsensitive;
            }
            else {
                listOptions = MemberListType.CaseSensitive;
            }

            if (allowPrefixLookup && name.endsWith("*")) {
                filterName = name.substring(0, name.length() - 1);
                prefixLookup = true;
                listOptions = MemberListType.All;
            }
        }

        return new FilterOptions(filterName, prefixLookup, ignoreCase, listOptions);
    }

    static Set<BindingFlags> filterPreCalculate(
        final boolean isPublic,
        final boolean isInherited,
        final boolean isStatic) {

        int mask = isPublic ? BindingFlags.Public.getMask() : BindingFlags.NonPublic.getMask();

        if (isInherited) {
            // We arrange things so the DeclaredOnly flag means "include inherited members"
            mask |= BindingFlags.DeclaredOnly.getMask();

            if (isStatic) {
                mask |= BindingFlags.Static.getMask() | BindingFlags.FlattenHierarchy.getMask();
            }
            else {
                mask |= BindingFlags.Instance.getMask();
            }
        }
        else {
            if (isStatic) {
                mask |= BindingFlags.Static.getMask();
            }
            else {
                mask |= BindingFlags.Instance.getMask();
            }
        }

        return BindingFlags.fromMask(mask);
    }

    @SuppressWarnings("PackageVisibleField")
    private final static class FilterOptions {
        final String name;
        final boolean prefixLookup;
        final boolean ignoreCase;
        final MemberListType listOptions;

        FilterOptions(final String name, final boolean prefixLookup, final boolean ignoreCase, final MemberListType listOptions) {
            this.name = name;
            this.prefixLookup = prefixLookup;
            this.ignoreCase = ignoreCase;
            this.listOptions = listOptions;
        }
    }

    // </editor-fold>
}
