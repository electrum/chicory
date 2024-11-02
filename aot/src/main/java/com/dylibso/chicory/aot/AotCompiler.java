package com.dylibso.chicory.aot;

import static com.dylibso.chicory.aot.AotEmitterMap.EMITTERS;
import static com.dylibso.chicory.aot.AotMethodInliner.aotMethodsRemapper;
import static com.dylibso.chicory.aot.AotMethodInliner.createAotMethodsClass;
import static com.dylibso.chicory.aot.AotMethodRefs.CALL_HOST_FUNCTION;
import static com.dylibso.chicory.aot.AotMethodRefs.CALL_INDIRECT;
import static com.dylibso.chicory.aot.AotMethodRefs.CHECK_INTERRUPTION;
import static com.dylibso.chicory.aot.AotMethodRefs.INSTANCE_MEMORY;
import static com.dylibso.chicory.aot.AotMethodRefs.INSTANCE_TABLE;
import static com.dylibso.chicory.aot.AotMethodRefs.TABLE_INSTANCE;
import static com.dylibso.chicory.aot.AotMethodRefs.TABLE_REF;
import static com.dylibso.chicory.aot.AotMethodRefs.THROW_CALL_STACK_EXHAUSTED;
import static com.dylibso.chicory.aot.AotMethodRefs.THROW_INDIRECT_CALL_TYPE_MISMATCH;
import static com.dylibso.chicory.aot.AotMethodRefs.THROW_UNKNOWN_FUNCTION;
import static com.dylibso.chicory.aot.AotUtil.callIndirectMethodName;
import static com.dylibso.chicory.aot.AotUtil.callIndirectMethodType;
import static com.dylibso.chicory.aot.AotUtil.defaultValue;
import static com.dylibso.chicory.aot.AotUtil.emitInvokeFunction;
import static com.dylibso.chicory.aot.AotUtil.emitInvokeStatic;
import static com.dylibso.chicory.aot.AotUtil.emitInvokeVirtual;
import static com.dylibso.chicory.aot.AotUtil.emitJvmToLong;
import static com.dylibso.chicory.aot.AotUtil.emitLongToJvm;
import static com.dylibso.chicory.aot.AotUtil.internalClassName;
import static com.dylibso.chicory.aot.AotUtil.jvmReturnType;
import static com.dylibso.chicory.aot.AotUtil.jvmType;
import static com.dylibso.chicory.aot.AotUtil.loadTypeOpcode;
import static com.dylibso.chicory.aot.AotUtil.localContextFieldName;
import static com.dylibso.chicory.aot.AotUtil.localType;
import static com.dylibso.chicory.aot.AotUtil.methodNameFor;
import static com.dylibso.chicory.aot.AotUtil.methodTypeFor;
import static com.dylibso.chicory.aot.AotUtil.returnTypeOpcode;
import static com.dylibso.chicory.aot.AotUtil.slotCount;
import static com.dylibso.chicory.aot.AotUtil.storeTypeOpcode;
import static com.dylibso.chicory.aot.AotUtil.valueMethodName;
import static com.dylibso.chicory.aot.AotUtil.valueMethodType;
import static com.dylibso.chicory.aot.MethodSplitter.computeSplits;
import static java.lang.invoke.MethodHandleProxies.asInterfaceInstance;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getObjectType;
import static org.objectweb.asm.Type.getType;

import com.dylibso.chicory.aot.MethodSplitter.ResultType;
import com.dylibso.chicory.aot.MethodSplitter.Split;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Module;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.FunctionSection;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValueType;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.util.CheckClassAdapter;

public final class AotCompiler {

    private static boolean isDebugFunction(int funcId) {
        // return false;
        // return true;
        // return funcId == 12;
        // return funcId != 15;
        // return funcId == 62;
        // return false;
        // return funcId == 3777;
        // return funcId == 10;
        return false;
    }

    /**
     * By default, HotSpot does not compile methods that are over 8000 bytes.
     * Compilation may be forced using the {@code -XX:-DontCompileHugeMethods} flag.
     * Try to stay under the limit, assuming a 3x expansion factor for WASM to bytecode.
     */
    public static final int HUGE_METHOD_SIZE = 2500;

    public static final String DEFAULT_CLASS_NAME = "com.dylibso.chicory.$gen.CompiledMachine";

    private static final MethodType CALL_METHOD_TYPE =
            methodType(long[].class, Instance.class, Memory.class, long[].class);

    private final AotClassLoader classLoader = new AotClassLoader();
    private final String className;
    private final int hugeMethodSize;
    private final Module module;
    private final AotAnalyzer analyzer;
    private final int functionImports;
    private final List<FunctionType> functionTypes;
    private final Map<String, byte[]> extraClasses;

    private AotCompiler(Module module, String className, int hugeMethodSize) {
        this.className = requireNonNull(className, "className");
        this.hugeMethodSize = hugeMethodSize;
        this.module = requireNonNull(module, "module");
        this.analyzer = new AotAnalyzer(module);
        this.functionImports = module.importSection().count(ExternalType.FUNCTION);
        this.functionTypes = analyzer.functionTypes();
        this.extraClasses = compileExtraClasses();
    }

    public static CompilerResult compileModule(Module module) {
        return compileModule(module, DEFAULT_CLASS_NAME);
    }

    public static CompilerResult compileModule(Module module, String className) {
        return compileModule(module, className, HUGE_METHOD_SIZE);
    }

    public static CompilerResult compileModule(
            Module module, String className, int hugeMethodSize) {

        var compiler = new AotCompiler(module, className, hugeMethodSize);

        var bytes = compiler.compileClass(module.functionSection());
        var factory = compiler.createMachineFactory(bytes);

        Map<String, byte[]> classBytes = new LinkedHashMap<>();
        classBytes.put(className, bytes);
        classBytes.putAll(compiler.extraClasses);
        return new CompilerResult(factory, classBytes);
    }

    private Function<Instance, Machine> createMachineFactory(byte[] classBytes) {
        try {
            var clazz = loadClass(classBytes).asSubclass(Machine.class);
            // convert constructor to factory interface
            var constructor = clazz.getConstructor(Instance.class);
            var handle = publicLookup().unreflectConstructor(constructor);
            @SuppressWarnings("unchecked")
            Function<Instance, Machine> function = asInterfaceInstance(Function.class, handle);
            return function;
        } catch (ReflectiveOperationException e) {
            throw new ChicoryException(e);
        }
    }

    private Class<?> loadClass(byte[] classBytes) {
        try {
            var clazz = classLoader.loadFromBytes(classBytes);
            // force initialization to run JVM verifier
            Class.forName(clazz.getName(), true, clazz.getClassLoader());
            return clazz;
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        } catch (VerifyError e) {
            // run ASM verifier to help with debugging
            try {
                var out = new StringWriter().append("ASM verifier:\n\n");
                CheckClassAdapter.verify(new ClassReader(classBytes), true, new PrintWriter(out));
                e.addSuppressed(new RuntimeException(out.toString()));
            } catch (Throwable t) {
                e.addSuppressed(t);
            }
            throw e;
        }
    }

    private void loadExtraClass(Map<String, byte[]> classes, byte[] bytes) {
        Class<?> clazz = loadClass(bytes);
        classes.put(clazz.getName(), bytes);
    }

    private Map<String, byte[]> compileExtraClasses() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        loadExtraClass(classes, createAotMethodsClass(className));
        compileContextClasses(classes);
        return classes;
    }

    private void compileContextClasses(Map<String, byte[]> classes) {
        for (int i = 0; i < module.functionSection().functionCount(); i++) {
            var funcId = functionImports + i;
            var type = functionTypes.get(funcId);
            var body = module.codeSection().getFunctionBody(i);

            if ((body.instructions().size() >= hugeMethodSize && type.returns().size() <= 1)
                    || isDebugFunction(funcId)) {
                var name = contextClassName(funcId);
                var bytes = compileContextClass(name, type, body);
                loadExtraClass(classes, bytes);
            }
        }
    }

    private byte[] compileContextClass(
            String contextClassName, FunctionType type, FunctionBody body) {

        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classWriter.visit(
                Opcodes.V11,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                internalClassName(contextClassName),
                null,
                getInternalName(Object.class),
                null);

        classWriter.visitSource("wasm", null);
        classWriter.visitNestHost(internalClassName(className));

        emitFunction(
                classWriter,
                "<init>",
                getMethodDescriptor(VOID_TYPE),
                false,
                asm -> {
                    emitCallSuper(asm);
                    asm.visitInsn(Opcodes.RETURN);
                });

        int localsCount = type.params().size() + body.localTypes().size();
        for (int i = 0; i < localsCount; i++) {
            classWriter.visitField(
                    Opcodes.ACC_PUBLIC,
                    localContextFieldName(i),
                    getDescriptor(jvmType(localType(type, body, i))),
                    null,
                    null);
        }

        return classWriter.toByteArray();
    }

    private byte[] compileClass(FunctionSection functions) {
        var internalClassName = internalClassName(className);

        ClassWriter binaryWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor classWriter = aotMethodsRemapper(binaryWriter, className);
        classWriter = new CheckClassAdapter(classWriter, true);

//        var printer = new PrintWriter(OutputStream.nullOutputStream());
//        var textifier = new CustomTextifier();
//        classWriter = new TraceClassVisitor(classWriter, textifier, printer);

        var finalClassWriter = classWriter;

        classWriter.visit(
                Opcodes.V11,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalClassName,
                null,
                getInternalName(Object.class),
                new String[] {getInternalName(Machine.class)});

        classWriter.visitSource("wasm", null);

        for (String name : extraClasses.keySet()) {
            classWriter.visitNestMember(internalClassName(name));
        }

        classWriter.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "instance",
                getDescriptor(Instance.class),
                null,
                null);

        // constructor
        emitFunction(
                classWriter,
                "<init>",
                methodType(void.class, Instance.class).toMethodDescriptorString(),
                false,
                asm -> compileMachineConstructor(internalClassName, asm));

        // Machine.call() implementation
        emitFunction(
                classWriter,
                "call",
                methodType(long[].class, int.class, long[].class).toMethodDescriptorString(),
                false,
                asm -> compileMachineCall(internalClassName, asm));

        // call_xxx() bridges for boxed to native
        for (int i = 0; i < functions.functionCount(); i++) {
            var funcId = functionImports + i;
            var type = functionTypes.get(funcId);
            emitFunction(
                    classWriter,
                    callMethodName(funcId),
                    CALL_METHOD_TYPE.toMethodDescriptorString(),
                    true,
                    asm -> compileCallFunction(internalClassName, funcId, type, asm));
        }

        // func_xxx() bridges for native to host functions
        for (int i = 0; i < functionImports; i++) {
            int funcId = i;
            var type = functionTypes.get(funcId);
            emitFunction(
                    classWriter,
                    methodNameFor(funcId),
                    methodTypeFor(type).toMethodDescriptorString(),
                    true,
                    asm -> compileHostFunction(funcId, type, asm));
        }

        // func_xxx() native function implementations
        for (int i = 0; i < functions.functionCount(); i++) {
            var funcId = functionImports + i;
            var type = functionTypes.get(funcId);
            var body = module.codeSection().getFunctionBody(i);

            if ((body.instructions().size() >= hugeMethodSize && type.returns().size() <= 1)
                    || isDebugFunction(funcId)) {
                emitFunction(
                        classWriter,
                        methodNameFor(funcId),
                        methodTypeFor(type).toMethodDescriptorString(),
                        true,
                        asm -> compileHugeStub(funcId, type, asm));
                emitFunction(
                        classWriter,
                        hugeOuterMethodName(funcId),
                        hugeOuterMethodDescriptor(internalContextClassName(funcId), type),
                        true,
                        asm -> compileHugeOuter(funcId, type, body, finalClassWriter, asm));
            } else {
                emitFunction(
                        classWriter,
                        methodNameFor(funcId),
                        methodTypeFor(type).toMethodDescriptorString(),
                        true,
                        asm -> compileBody(funcId, type, body, asm));
            }
        }

        // call_indirect_xxx() bridges for native CALL_INDIRECT
        var allTypes = module.typeSection().types();
        for (int i = 0; i < allTypes.length; i++) {
            var typeId = i;
            var type = allTypes[i];
            emitFunction(
                    classWriter,
                    callIndirectMethodName(typeId),
                    callIndirectMethodType(type).toMethodDescriptorString(),
                    true,
                    asm -> compileCallIndirect(typeId, type, asm));
        }

        // value_xxx() bridges for multi-value return
        var returnTypes =
                functionTypes.stream()
                        .map(FunctionType::returns)
                        .filter(types -> types.size() > 1)
                        .collect(toSet());
        for (var types : returnTypes) {
            emitFunction(
                    classWriter,
                    valueMethodName(types),
                    valueMethodType(types).toMethodDescriptorString(),
                    true,
                    asm -> {
                        emitBoxArguments(asm, types);
                        asm.visitInsn(Opcodes.ARETURN);
                    });
        }

        classWriter.visitEnd();

        try {
            return binaryWriter.toByteArray();
        } catch (MethodTooLargeException e) {
            String name = e.getMethodName();
            if (name.startsWith("func_") && module.nameSection() != null) {
                int funcId = Integer.parseInt(name.split("_", -1)[1]);
                String function = module.nameSection().nameOfFunction(funcId);
                if (function != null) {
                    name += " (" + function + ")";
                }
            }
            throw new ChicoryException(
                    String.format(
                            "JVM bytecode too large for WASM method: %s size=%d",
                            name, e.getCodeSize()),
                    e);
        }
    }

    private static void emitFunction(
            ClassVisitor classWriter,
            String methodName,
            String descriptor,
            boolean isStatic,
            Consumer<MethodVisitor> consumer) {

        var methodWriter =
                classWriter.visitMethod(
                        Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0),
                        methodName,
                        descriptor,
                        null,
                        null);

        // optimize instruction size to avoid method size limits
        methodWriter = new InstructionAdapter(methodWriter);

        methodWriter.visitCode();
        consumer.accept(methodWriter);
        methodWriter.visitMaxs(0, 0);
        methodWriter.visitEnd();
    }

    private static void emitCallSuper(MethodVisitor asm) {
        asm.visitVarInsn(Opcodes.ALOAD, 0);
        asm.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                getInternalName(Object.class),
                "<init>",
                getMethodDescriptor(VOID_TYPE),
                false);
    }

    private static void compileMachineConstructor(String internalClassName, MethodVisitor asm) {
        emitCallSuper(asm);

        // this.instance = instance;
        asm.visitVarInsn(Opcodes.ALOAD, 0);
        asm.visitVarInsn(Opcodes.ALOAD, 1);
        asm.visitFieldInsn(
                Opcodes.PUTFIELD, internalClassName, "instance", getDescriptor(Instance.class));

        asm.visitInsn(Opcodes.RETURN);
    }

    private void compileMachineCall(String internalClassName, MethodVisitor asm) {
        // handle modules with no functions
        if (functionTypes.isEmpty()) {
            asm.visitVarInsn(Opcodes.ILOAD, 1);
            emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
            asm.visitInsn(Opcodes.ATHROW);
            return;
        }

        // try block
        Label start = new Label();
        Label end = new Label();
        asm.visitTryCatchBlock(start, end, end, getInternalName(StackOverflowError.class));
        asm.visitLabel(start);

        // prepare arguments
        asm.visitVarInsn(Opcodes.ALOAD, 0);
        asm.visitFieldInsn(
                Opcodes.GETFIELD, internalClassName, "instance", getDescriptor(Instance.class));
        asm.visitInsn(Opcodes.DUP);
        emitInvokeVirtual(asm, INSTANCE_MEMORY);
        asm.visitVarInsn(Opcodes.ALOAD, 2);

        // switch (funcId)
        Label defaultLabel = new Label();
        Label hostLabel = new Label();
        Label[] labels = new Label[Math.min(functionTypes.size(), 1100)];

        for (int i = 0; i < labels.length; i++) {
            labels[i] = (i < functionImports) ? hostLabel : new Label();
        }

        asm.visitVarInsn(Opcodes.ILOAD, 1);
        asm.visitTableSwitchInsn(0, labels.length - 1, defaultLabel, labels);

        // return call_xxx(instance, memory, args);
        for (int i = functionImports; i < labels.length; i++) {
            asm.visitLabel(labels[i]);
            asm.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    internalClassName,
                    callMethodName(i),
                    CALL_METHOD_TYPE.toMethodDescriptorString(),
                    false);
            asm.visitInsn(Opcodes.ARETURN);
        }

        // return instance.callHostFunction(funcId, args);
        if (functionImports > 0) {
            asm.visitLabel(hostLabel);
            asm.visitInsn(Opcodes.POP);
            asm.visitInsn(Opcodes.POP);
            asm.visitVarInsn(Opcodes.ILOAD, 1);
            asm.visitVarInsn(Opcodes.ALOAD, 2);
            emitInvokeStatic(asm, CALL_HOST_FUNCTION);
            asm.visitInsn(Opcodes.ARETURN);
        }

        // throw new InvalidException("unknown function " + funcId);
        asm.visitLabel(defaultLabel);
        asm.visitVarInsn(Opcodes.ILOAD, 1);
        emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
        asm.visitInsn(Opcodes.ATHROW);

        // catch StackOverflow
        asm.visitLabel(end);
        emitInvokeStatic(asm, THROW_CALL_STACK_EXHAUSTED);
        asm.visitInsn(Opcodes.ATHROW);
    }

    private static void compileCallFunction(
            String internalClassName, int funcId, FunctionType type, MethodVisitor asm) {
        // unbox the arguments from long[]
        for (int i = 0; i < type.params().size(); i++) {
            var param = type.params().get(i);
            asm.visitVarInsn(Opcodes.ALOAD, 2);
            asm.visitLdcInsn(i);
            asm.visitInsn(Opcodes.LALOAD);
            emitLongToJvm(asm, param);
        }

        asm.visitVarInsn(Opcodes.ALOAD, 1);
        asm.visitVarInsn(Opcodes.ALOAD, 0);

        emitInvokeFunction(asm, internalClassName, funcId, type);

        // box the result into long[]
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            asm.visitLdcInsn(0);
            asm.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
        } else if (returnType != long[].class) {
            emitJvmToLong(asm, type.returns().get(0));
            asm.visitVarInsn(Opcodes.LSTORE, 3);
            asm.visitLdcInsn(1);
            asm.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
            asm.visitInsn(Opcodes.DUP);
            asm.visitLdcInsn(0);
            asm.visitVarInsn(Opcodes.LLOAD, 3);
            asm.visitInsn(Opcodes.LASTORE);
        }
        asm.visitInsn(Opcodes.ARETURN);
    }

    private void compileCallIndirect(int typeId, FunctionType type, MethodVisitor asm) {
        int slots = type.params().stream().mapToInt(AotUtil::slotCount).sum();
        int funcTableIdx = slots;
        int tableIdx = slots + 1;
        int memory = slots + 2;
        int instance = slots + 3;
        int table = slots + 4;
        int funcId = slots + 5;
        int refInstance = slots + 6;

        emitInvokeStatic(asm, CHECK_INTERRUPTION);

        // TableInstance table = instance.table(tableIdx);
        asm.visitVarInsn(Opcodes.ALOAD, instance);
        asm.visitVarInsn(Opcodes.ILOAD, tableIdx);
        emitInvokeVirtual(asm, INSTANCE_TABLE);
        asm.visitVarInsn(Opcodes.ASTORE, table);

        // int funcId = tableRef(table, funcTableIdx);
        asm.visitVarInsn(Opcodes.ALOAD, table);
        asm.visitVarInsn(Opcodes.ILOAD, funcTableIdx);
        emitInvokeStatic(asm, TABLE_REF);
        asm.visitVarInsn(Opcodes.ISTORE, funcId);

        // Instance refInstance = table.instance(funcTableIdx);
        asm.visitVarInsn(Opcodes.ALOAD, table);
        asm.visitVarInsn(Opcodes.ILOAD, funcTableIdx);
        emitInvokeVirtual(asm, TABLE_INSTANCE);
        asm.visitVarInsn(Opcodes.ASTORE, refInstance);

        Label local = new Label();
        Label other = new Label();

        // if (refInstance == null || refInstance == instance)
        asm.visitVarInsn(Opcodes.ALOAD, refInstance);
        asm.visitJumpInsn(Opcodes.IFNULL, local);
        asm.visitVarInsn(Opcodes.ALOAD, refInstance);
        asm.visitVarInsn(Opcodes.ALOAD, instance);
        asm.visitJumpInsn(Opcodes.IF_ACMPNE, other);

        // local: call function in this module
        asm.visitLabel(local);

        int slot = 0;
        for (ValueType param : type.params()) {
            asm.visitVarInsn(loadTypeOpcode(param), slot);
            slot += slotCount(param);
        }
        asm.visitVarInsn(Opcodes.ALOAD, memory);
        asm.visitVarInsn(Opcodes.ALOAD, instance);

        List<Integer> validIds = new ArrayList<>();
        for (int i = 0; i < functionTypes.size(); i++) {
            if (type.equals(functionTypes.get(i))) {
                validIds.add(i);
            }
        }

        Label invalid = new Label();
        int[] keys = validIds.stream().mapToInt(x -> x).toArray();
        Label[] labels = validIds.stream().map(x -> new Label()).toArray(Label[]::new);

        asm.visitVarInsn(Opcodes.ILOAD, funcId);
        asm.visitLookupSwitchInsn(invalid, keys, labels);

        for (int i = 0; i < validIds.size(); i++) {
            asm.visitLabel(labels[i]);
            emitInvokeFunction(asm, internalClassName(className), keys[i], type);
            asm.visitInsn(returnTypeOpcode(type));
        }

        asm.visitLabel(invalid);
        emitInvokeStatic(asm, THROW_INDIRECT_CALL_TYPE_MISMATCH);
        asm.visitInsn(Opcodes.ATHROW);

        // other: call function in another module
        asm.visitLabel(other);

        emitBoxArguments(asm, type.params());
        asm.visitLdcInsn(typeId);
        asm.visitVarInsn(Opcodes.ILOAD, funcId);
        asm.visitVarInsn(Opcodes.ALOAD, refInstance);

        emitInvokeStatic(asm, CALL_INDIRECT);

        emitUnboxResult(type, asm);
    }

    private static void compileHostFunction(int funcId, FunctionType type, MethodVisitor asm) {
        int slot = type.params().stream().mapToInt(AotUtil::slotCount).sum();

        asm.visitVarInsn(Opcodes.ALOAD, slot + 1); // instance
        asm.visitLdcInsn(funcId);
        emitBoxArguments(asm, type.params());

        emitInvokeStatic(asm, CALL_HOST_FUNCTION);

        emitUnboxResult(type, asm);
    }

    private static void emitBoxArguments(MethodVisitor asm, List<ValueType> types) {
        int slot = 0;
        // box the arguments into long[]
        asm.visitLdcInsn(types.size());
        asm.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG); // long
        for (int i = 0; i < types.size(); i++) {
            asm.visitInsn(Opcodes.DUP);
            asm.visitLdcInsn(i);
            ValueType valueType = types.get(i);
            asm.visitVarInsn(loadTypeOpcode(valueType), slot);
            emitJvmToLong(asm, valueType);
            asm.visitInsn(Opcodes.LASTORE);
            slot += slotCount(valueType);
        }
    }

    private static void emitUnboxResult(FunctionType type, MethodVisitor asm) {
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            asm.visitInsn(Opcodes.RETURN);
        } else if (returnType == long[].class) {
            asm.visitInsn(Opcodes.ARETURN);
        } else {
            // unbox the result from long[0]
            asm.visitLdcInsn(0);
            asm.visitInsn(Opcodes.LALOAD);
            emitLongToJvm(asm, type.returns().get(0));
            asm.visitInsn(returnTypeOpcode(type));
        }
    }

    private void compileHugeStub(int funcId, FunctionType type, MethodVisitor asm) {

        var internalContextClassName = internalContextClassName(funcId);

        // create context instance
        asm.visitTypeInsn(Opcodes.NEW, internalContextClassName);
        asm.visitInsn(Opcodes.DUP);
        asm.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                internalContextClassName,
                "<init>",
                getMethodDescriptor(VOID_TYPE),
                false);

        // copy parameters to context
        int slot = 0;
        List<ValueType> params = type.params();
        for (int i = 0; i < params.size(); i++) {
            ValueType param = params.get(i);
            asm.visitInsn(Opcodes.DUP);
            asm.visitVarInsn(loadTypeOpcode(param), slot);
            asm.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    internalContextClassName,
                    localContextFieldName(i),
                    getDescriptor(jvmType(param)));
            slot += slotCount(param);
        }

        // invoke outer method
        asm.visitVarInsn(Opcodes.ALOAD, slot);
        asm.visitVarInsn(Opcodes.ALOAD, slot + 1);
        asm.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                internalClassName(className),
                hugeOuterMethodName(funcId),
                hugeOuterMethodDescriptor(internalContextClassName, type),
                false);
        asm.visitInsn(returnTypeOpcode(type));
    }

    private void compileHugeOuter(
            int funcId,
            FunctionType type,
            FunctionBody body,
            ClassVisitor classWriter,
            MethodVisitor asm) {
        var ctx =
                new AotContext(
                        internalClassName(className),
                        internalContextClassName(funcId),
                        analyzer.globalTypes(),
                        functionTypes,
                        module.typeSection().types(),
                        List.of(),
                        true,
                        funcId,
                        type,
                        body);

        List<AotInstruction> instructions = analyzer.analyze(ctx.funcId());

        List<Split> splits = List.of();
        if (isDebugFunction(funcId)) {
            // printMethod(ctx, instructions);
            System.out.println("func_" + funcId + " " + type);
            splits = computeSplits(instructions);

            splits = splits.stream()
                    .filter(split -> split.end() - split.start() >= 50)
                    .collect(toList());
            System.out.println("SPLIT COUNT: " + splits.size());
            for (Split split : splits) {
                System.out.println("SPLIT SIZE: " + (split.end() - split.start()));
            }
        }

        compileBody(ctx, type, splits, classWriter, asm);
    }

    static void printMethod(AotContext ctx, List<AotInstruction> instructions) {
        System.out.println("[IR " + ctx.funcId() + "]");
        for (var ins : instructions) {
            printInstruction(ins);
        }
        System.out.println();
    }

    static void printInstruction(AotInstruction ins) {
        // System.out.printf("  %-20s << %s %s%n", ins, ins.minStack(), ins.stack());
    }

    private void compileBody(int funcId, FunctionType type, FunctionBody body, MethodVisitor asm) {
        var ctx =
                new AotContext(
                        internalClassName(className),
                        null,
                        analyzer.globalTypes(),
                        functionTypes,
                        module.typeSection().types(),
                        type.params(),
                        false,
                        funcId,
                        type,
                        body);

        // initialize local variables to their default values
        int localsCount = type.params().size() + body.localTypes().size();
        for (int i = type.params().size(); i < localsCount; i++) {
            var localType = localType(type, body, i);
            asm.visitLdcInsn(defaultValue(localType));
            asm.visitVarInsn(storeTypeOpcode(localType), ctx.localSlotIndex(i));
        }

        compileBody(ctx, type, List.of(), null, asm);
    }

    @SuppressWarnings("checkstyle:modifiedcontrolvariable")
    private void compileBody(
            AotContext ctx,
            FunctionType type,
            List<Split> splits,
            ClassVisitor classWriter,
            MethodVisitor asm) {

        List<AotInstruction> instructions = analyzer.analyze(ctx.funcId());
        if (isDebugFunction(ctx.funcId()) && !ctx.huge()) {
            // printMethod(ctx, instructions);
        }

        var splitStarts = splits.stream().collect(toMap(Split::start, identity()));

        // allocate labels for all label targets
        Map<Long, Label> labels = new HashMap<>();
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.put(target, new Label());
            }
        }

        // track targets to detect backward jumps
        Set<Long> visitedTargets = new HashSet<>();

        // compile the function body
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (isDebugFunction(ctx.funcId())) {
                // printInstruction(ins);
            }

            // compile inner method if this is the start of a split
            if (splitStarts.containsKey(idx)) {
                Split split = splitStarts.get(idx);

                // emit label
                emitInstruction(ctx, ins, labels, visitedTargets, asm);

                // generate inner method
                System.out.println();
                System.out.println(">>>>> INNER >>>>>");
                System.out.println(split);
                AotContext innerCtx = ctx.copyForInner(split.params());
                emitFunction(
                        classWriter,
                        hugeInnerMethodName(ctx.funcId(), split),
                        hugeInnerMethodDescriptor(internalContextClassName(ctx.funcId()), split),
                        true,
                        methodWriter ->
                                compileHugeInnerBody(
                                        innerCtx, type, instructions, split, methodWriter));
                System.out.println("<<<<<<<<<<<<<<<<<");
                System.out.println();

                // invoke inner method
                asm.visitVarInsn(Opcodes.ALOAD, ctx.contextSlot());
                asm.visitVarInsn(Opcodes.ALOAD, ctx.memorySlot());
                asm.visitVarInsn(Opcodes.ALOAD, ctx.instanceSlot());
                asm.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        ctx.internalClassName(),
                        hugeInnerMethodName(ctx.funcId(), split),
                        hugeInnerMethodDescriptor(internalContextClassName(ctx.funcId()), split),
                        false);

                // TODO: special case single result and use default for first entry in table
                // generate a table switch to handle results
                Label[] resultLabels = new Label[split.results().size()];
                for (int i = 0; i < split.results().size(); i++) {
                    var result = split.results().get(i);
                    if (result.type() == ResultType.GOTO && result.types().isEmpty()) {
                        resultLabels[i] = labels.get(result.target());
                    } else {
                        resultLabels[i] = new Label();
                    }
                }
                asm.visitInsn(Opcodes.DUP);
                asm.visitVarInsn(Opcodes.ASTORE, ctx.tempSlot());
                asm.visitLdcInsn(0);
                asm.visitInsn(Opcodes.LALOAD);
                asm.visitInsn(Opcodes.L2I);
                asm.visitTableSwitchInsn(0, resultLabels.length - 1, resultLabels[0], resultLabels);

                // generate result targets in reverse order so that continue is last
                for (int i = split.results().size() - 1; i >= 0; i--) {
                    var result = split.results().get(i);
                    if (result.type() != ResultType.GOTO || !result.types().isEmpty()) {
                        asm.visitLabel(resultLabels[i]);
                        if (result.type() == ResultType.RETURN) {
                            if (type.returns().isEmpty()) {
                                asm.visitInsn(Opcodes.RETURN);
                            } else {
                                if (type.returns().size() > 1) {
                                    throw new ChicoryException("Multi-value return not supported");
                                }
                                asm.visitVarInsn(Opcodes.ALOAD, ctx.tempSlot());
                                asm.visitLdcInsn(1);
                                asm.visitInsn(Opcodes.LALOAD);
                                emitLongToJvm(asm, type.returns().get(0));
                                asm.visitInsn(returnTypeOpcode(type));
                            }
                        } else {
                            // push the produced values onto the stack
                            for (int j = 0; j < result.types().size(); j++) {
                                asm.visitVarInsn(Opcodes.ALOAD, ctx.tempSlot());
                                asm.visitLdcInsn(j + 1);
                                asm.visitInsn(Opcodes.LALOAD);
                                emitLongToJvm(asm, result.types().get(j));
                            }
                            if (result.type() == ResultType.GOTO) {
                                asm.visitJumpInsn(Opcodes.GOTO, labels.get(result.target()));
                            }
                        }
                    }
                }

                idx = split.end() - 1;
                continue;
            }

            emitInstruction(ctx, ins, labels, visitedTargets, asm);
        }
    }

    private static void emitInstruction(
            AotContext ctx,
            AotInstruction ins,
            Map<Long, Label> labels,
            Set<Long> visitedTargets,
            MethodVisitor asm) {

        switch (ins.opcode()) {
            case LABEL:
                Label label = labels.get(ins.operand(0));
                if (label != null) {
                    asm.visitLabel(label);
                    visitedTargets.add(ins.operand(0));
                }
                break;
            case GOTO:
                if (visitedTargets.contains(ins.operand(0))) {
                    emitInvokeStatic(asm, CHECK_INTERRUPTION);
                }
                asm.visitJumpInsn(Opcodes.GOTO, labels.get(ins.operand(0)));
                break;
            case IFEQ:
                if (visitedTargets.contains(ins.operand(0))) {
                    throw new ChicoryException("Unexpected backward jump");
                }
                asm.visitJumpInsn(Opcodes.IFEQ, labels.get(ins.operand(0)));
                break;
            case IFNE:
                if (visitedTargets.contains(ins.operand(0))) {
                    Label skip = new Label();
                    asm.visitJumpInsn(Opcodes.IFEQ, skip);
                    emitInvokeStatic(asm, CHECK_INTERRUPTION);
                    asm.visitJumpInsn(Opcodes.GOTO, labels.get(ins.operand(0)));
                    asm.visitLabel(skip);
                } else {
                    asm.visitJumpInsn(Opcodes.IFNE, labels.get(ins.operand(0)));
                }
                break;
            case SWITCH:
                if (ins.operands().anyMatch(visitedTargets::contains)) {
                    emitInvokeStatic(asm, CHECK_INTERRUPTION);
                }
                // table switch using the last entry of the table as the default
                Label[] table = new Label[ins.operandCount() - 1];
                for (int i = 0; i < table.length; i++) {
                    table[i] = labels.get(ins.operand(i));
                }
                Label defaultLabel = labels.get(ins.operand(table.length));
                asm.visitTableSwitchInsn(0, table.length - 1, defaultLabel, table);
                break;
            default:
                var emitter = EMITTERS.get(ins.opcode());
                if (emitter == null) {
                    throw new ChicoryException("Unhandled opcode: " + ins.opcode());
                }
                emitter.emit(ctx, ins, asm);
        }
    }

    private static void compileHugeInnerBody(
            AotContext ctx,
            FunctionType type,
            List<AotInstruction> instructions,
            Split split,
            MethodVisitor asm) {

        // push parameters onto the stack
        for (int i = 0; i < split.params().size(); i++) {
            var param = split.params().get(i);
            asm.visitVarInsn(loadTypeOpcode(param), ctx.localSlotIndex(i));
        }

        // range of instructions to compile
        int startIdx = split.start();
        int endIdx = split.end();

        // allocate labels for all label targets
        Map<Long, Label> labels = new HashMap<>();
        for (int idx = startIdx; idx < endIdx; idx++) {
            for (long target : instructions.get(idx).labelTargets()) {
                labels.put(target, new Label());
                System.out.println("LABEL: " + target + " => " + labels.get(target));
            }
        }

        // track targets to detect backward jumps
        Set<Long> visitedTargets = new HashSet<>();

        // find the return id
        OptionalLong returnId = OptionalLong.empty();
        for (int i = 0; i < split.results().size(); i++) {
            if (split.results().get(i).type() == ResultType.RETURN) {
                returnId = OptionalLong.of(i);
                break;
            }
        }

        // compile the function body
        if (isDebugFunction(ctx.funcId())) {
            System.out.println(hugeInnerMethodName(ctx.funcId(), split));
        }
        for (int idx = startIdx; idx < endIdx; idx++) {
            var ins = instructions.get(idx);
            if (isDebugFunction(ctx.funcId())) {
                // printInstruction(ins);
            }

            if (ins.opcode() == AotOpCode.RETURN) {
                // TODO: generate methods for these and handle multi-value returns
                if (type.returns().isEmpty()) {
                    asm.visitLdcInsn(1);
                    asm.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
                    asm.visitInsn(Opcodes.DUP);
                    asm.visitLdcInsn(0);
                    asm.visitLdcInsn(returnId.orElseThrow());
                    asm.visitInsn(Opcodes.LASTORE);
                } else if (type.returns().size() == 1) {
                    var returnType = type.returns().get(0);
                    asm.visitVarInsn(storeTypeOpcode(returnType), ctx.tempSlot());
                    asm.visitLdcInsn(2);
                    asm.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
                    asm.visitInsn(Opcodes.DUP);
                    asm.visitLdcInsn(0);
                    asm.visitLdcInsn(returnId.orElseThrow());
                    asm.visitInsn(Opcodes.LASTORE);
                    asm.visitInsn(Opcodes.DUP);
                    asm.visitLdcInsn(1);
                    asm.visitVarInsn(loadTypeOpcode(returnType), ctx.tempSlot());
                    emitJvmToLong(asm, returnType);
                    asm.visitInsn(Opcodes.LASTORE);
                } else {
                    throw new ChicoryException("Multi-value return not supported");
                }
                asm.visitInsn(Opcodes.ARETURN);
            } else {
                emitInstruction(ctx, ins, labels, visitedTargets, asm);
            }
        }

        // continue execution with produced values
        if (split.results().get(0).type() == ResultType.CONTINUE) {
            System.out.println("CONTINUE RESULT: " + split.results().get(0));
            emitInnerResult(ctx, split, 0, asm);
        }

        // generate local targets for labels outside method
        for (int i = 0; i < split.results().size(); i++) {
            var result = split.results().get(i);
            if (result.type() == ResultType.GOTO) {
                System.out.println("GOTO RESULT: " + result);
                asm.visitLabel(labels.get(result.target()));
                emitInnerResult(ctx, split, i, asm);
            }
        }
    }

    private static void emitInnerResult(AotContext ctx, Split split, int idx, MethodVisitor asm) {
        var types = split.results().get(idx).types();
        int slot = ctx.tempSlot();
        for (ValueType resultType : types) {
            asm.visitVarInsn(storeTypeOpcode(resultType), slot);
            slot += slotCount(resultType);
        }
        asm.visitLdcInsn(types.size() + 1);
        asm.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
        asm.visitInsn(Opcodes.DUP);
        asm.visitLdcInsn(0);
        asm.visitLdcInsn((long) idx);
        asm.visitInsn(Opcodes.LASTORE);
        for (int i = 0; i < types.size(); i++) {
            slot -= slotCount(types.get(i));
            ValueType valueType = types.get(i);
            asm.visitInsn(Opcodes.DUP);
            asm.visitLdcInsn(i + 1);
            asm.visitVarInsn(loadTypeOpcode(valueType), slot);
            emitJvmToLong(asm, valueType);
            asm.visitInsn(Opcodes.LASTORE);
        }
        asm.visitInsn(Opcodes.ARETURN);
    }

    private String contextClassName(int funcId) {
        return className + "$Context" + funcId;
    }

    private String internalContextClassName(int funcId) {
        return internalClassName(contextClassName(funcId));
    }

    private static String hugeOuterMethodName(int funcId) {
        return methodNameFor(funcId) + "_outer";
    }

    private static String hugeOuterMethodDescriptor(
            String internalContextClassName, FunctionType type) {
        return getMethodDescriptor(
                getType(jvmReturnType(type)),
                getObjectType(internalContextClassName),
                getType(Memory.class),
                getType(Instance.class));
    }

    private static String hugeInnerMethodName(int funcId, Split split) {
        return methodNameFor(funcId) + "_inner_" + split.start() + "_" + split.end();
    }

    private static String hugeInnerMethodDescriptor(String internalContextClassName, Split split) {
        List<Type> parameters = new ArrayList<>(split.params().size() + 3);
        for (var param : split.params()) {
            parameters.add(getType(jvmType(param)));
        }
        parameters.add(getObjectType(internalContextClassName));
        parameters.add(getType(Memory.class));
        parameters.add(getType(Instance.class));
        return getMethodDescriptor(getType(long[].class), parameters.toArray(new Type[0]));
    }

    private static String callMethodName(int functId) {
        return "call_" + functId;
    }
}
