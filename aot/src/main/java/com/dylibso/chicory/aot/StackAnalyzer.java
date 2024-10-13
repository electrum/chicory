package com.dylibso.chicory.aot;

import static com.dylibso.chicory.aot.AotUtil.localType;
import static java.util.Collections.reverse;
import static java.util.stream.Collectors.toUnmodifiableList;

import com.dylibso.chicory.wasm.Module;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Global;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.TableImport;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

final class StackAnalyzer {

    private final TypeStack stack = new TypeStack();
    private final Module module;
    private final List<ValueType> globalTypes;
    private final List<FunctionType> functionTypes;
    private final List<ValueType> tableTypes;
    private final FunctionType functionType;
    private final FunctionBody body;

    private int index = 0;
    private int exitBlockDepth = -1;

    public StackAnalyzer(Module module, int funcId) {
        this.module = module;
        this.globalTypes = getGlobalTypes(module);
        this.functionTypes = getFunctionTypes(module);
        this.functionType = functionTypes.get(funcId);
        this.tableTypes = getTableTypes(module);
        int functionImports = module.importSection().count(ExternalType.FUNCTION);
        this.body = module.codeSection().getFunctionBody(funcId - functionImports);

        // implicit block for the function
        stack.enterScope(FunctionType.of(List.of(), functionType.returns()));
    }

    public int index() {
        return index;
    }

    public boolean hasNext() {
        return index < body.instructions().size();
    }

    public void analyze() {
        // skip instructions after unconditional control transfer
        if (exitBlockDepth >= 0) {
            while (hasNext()) {
                var ins = body.instructions().get(index);
                if (ins.depth() > exitBlockDepth
                        || ins.opcode() != OpCode.ELSE && ins.opcode() != OpCode.END) {
                    index++;
                    continue;
                }

                exitBlockDepth = -1;
                if (ins.opcode() == OpCode.END) {
                    stack.scopeRestore();
                }
                break;
            }
        }

        var ins = body.instructions().get(index);
        switch (ins.opcode()) {
            case NOP:
                break;
            case BLOCK:
            case LOOP:
                stack.enterScope(blockType(ins));
                break;
            case END:
                stack.exitScope();
                break;
            case RETURN:
                exitBlockDepth = ins.depth();
                for (var type : reversed(functionType.returns())) {
                    stack.pop(type);
                }
                break;
            case IF:
                stack.pop(ValueType.I32);
                stack.enterScope(blockType(ins));
                // use the same starting stack sizes for both sides of the branch
                if (body.instructions().get(ins.labelFalse() - 1).opcode() == OpCode.ELSE) {
                    stack.pushTypes();
                }
                break;
            case ELSE:
                stack.popTypes();
                break;
            case UNREACHABLE:
            case BR:
                exitBlockDepth = ins.depth();
                break;
            case BR_IF:
                stack.pop(ValueType.I32);
                break;
            case BR_TABLE:
                exitBlockDepth = ins.depth();
                stack.pop(ValueType.I32);
                break;
            default:
                updateStack(ins);
        }

        index++;

        if (!hasNext()) {
            // implicit return at end of function
            for (var type : reversed(functionType.returns())) {
                stack.pop(type);
            }

            stack.verifyEmpty();
        }
    }

    private void updateStack(Instruction ins) {
        switch (ins.opcode()) {
            case I32_CLZ:
            case I32_CTZ:
            case I32_EQZ:
            case I32_EXTEND_16_S:
            case I32_EXTEND_8_S:
            case I32_LOAD16_S:
            case I32_LOAD16_U:
            case I32_LOAD8_S:
            case I32_LOAD8_U:
            case I32_LOAD:
            case I32_POPCNT:
            case MEMORY_GROW:
                // [I32] -> [I32]
                stack.pop(ValueType.I32);
                stack.push(ValueType.I32);
                break;
            case F32_CONVERT_I32_S:
            case F32_CONVERT_I32_U:
            case F32_LOAD:
            case F32_REINTERPRET_I32:
                // [I32] -> [F32]
                stack.pop(ValueType.I32);
                stack.push(ValueType.F32);
                break;
            case F32_ABS:
            case F32_CEIL:
            case F32_FLOOR:
            case F32_NEAREST:
            case F32_NEG:
            case F32_SQRT:
            case F32_TRUNC:
                // [F32] -> [F32]
                stack.pop(ValueType.F32);
                stack.push(ValueType.F32);
                break;
            case I32_REINTERPRET_F32:
            case I32_TRUNC_F32_S:
            case I32_TRUNC_F32_U:
            case I32_TRUNC_SAT_F32_S:
            case I32_TRUNC_SAT_F32_U:
                // [F32] -> [I32]
                stack.pop(ValueType.F32);
                stack.push(ValueType.I32);
                break;
            case I32_WRAP_I64:
            case I64_EQZ:
                // [I64] -> [I32]
                stack.pop(ValueType.I64);
                stack.push(ValueType.I32);
                break;
            case F32_CONVERT_I64_S:
            case F32_CONVERT_I64_U:
                // [I64] -> [F32]
                stack.pop(ValueType.I64);
                stack.push(ValueType.F32);
                break;
            case F32_DEMOTE_F64:
                // [F64] -> [F32]
                stack.pop(ValueType.F64);
                stack.push(ValueType.F32);
                break;
            case I32_TRUNC_F64_S:
            case I32_TRUNC_F64_U:
            case I32_TRUNC_SAT_F64_S:
            case I32_TRUNC_SAT_F64_U:
                // [F64] -> [I32]
                stack.pop(ValueType.F64);
                stack.push(ValueType.I32);
                break;
            case I32_ADD:
            case I32_AND:
            case I32_DIV_S:
            case I32_DIV_U:
            case I32_EQ:
            case I32_GE_S:
            case I32_GE_U:
            case I32_GT_S:
            case I32_GT_U:
            case I32_LE_S:
            case I32_LE_U:
            case I32_LT_S:
            case I32_LT_U:
            case I32_MUL:
            case I32_NE:
            case I32_OR:
            case I32_REM_S:
            case I32_REM_U:
            case I32_ROTL:
            case I32_ROTR:
            case I32_SHL:
            case I32_SHR_S:
            case I32_SHR_U:
            case I32_SUB:
            case I32_XOR:
                // [I32 I32] -> [I32]
                stack.pop(ValueType.I32);
                stack.pop(ValueType.I32);
                stack.push(ValueType.I32);
                break;
            case I64_EQ:
            case I64_GE_S:
            case I64_GE_U:
            case I64_GT_S:
            case I64_GT_U:
            case I64_LE_S:
            case I64_LE_U:
            case I64_LT_S:
            case I64_LT_U:
            case I64_NE:
                // [I64 I64] -> [I32]
                stack.pop(ValueType.I64);
                stack.pop(ValueType.I64);
                stack.push(ValueType.I32);
                break;
            case F32_ADD:
            case F32_COPYSIGN:
            case F32_DIV:
            case F32_MAX:
            case F32_MIN:
            case F32_MUL:
            case F32_SUB:
                // [F32 F32] -> [F32]
                stack.pop(ValueType.F32);
                stack.pop(ValueType.F32);
                stack.push(ValueType.F32);
                break;
            case F32_EQ:
            case F32_GE:
            case F32_GT:
            case F32_LE:
            case F32_LT:
            case F32_NE:
                // [F32 F32] -> [I32]
                stack.pop(ValueType.F32);
                stack.pop(ValueType.F32);
                stack.push(ValueType.I32);
                break;
            case F64_EQ:
            case F64_GE:
            case F64_GT:
            case F64_LE:
            case F64_LT:
            case F64_NE:
                // [F64 F64] -> [I32]
                stack.pop(ValueType.F64);
                stack.pop(ValueType.F64);
                stack.push(ValueType.I32);
                break;
            case I64_CLZ:
            case I64_CTZ:
            case I64_EXTEND_16_S:
            case I64_EXTEND_32_S:
            case I64_EXTEND_8_S:
            case I64_POPCNT:
                // [I64] -> [I64]
                stack.pop(ValueType.I64);
                stack.push(ValueType.I64);
                break;
            case I64_REINTERPRET_F64:
            case I64_TRUNC_F64_S:
            case I64_TRUNC_F64_U:
            case I64_TRUNC_SAT_F64_S:
            case I64_TRUNC_SAT_F64_U:
                // [F64] -> [I64]
                stack.pop(ValueType.F64);
                stack.push(ValueType.I64);
                break;
            case F64_TRUNC:
            case F64_SQRT:
            case F64_NEAREST:
            case F64_ABS:
            case F64_CEIL:
            case F64_FLOOR:
            case F64_NEG:
                // [F64] -> [F64]
                stack.pop(ValueType.F64);
                stack.push(ValueType.F64);
                break;
            case F64_CONVERT_I64_S:
            case F64_CONVERT_I64_U:
            case F64_REINTERPRET_I64:
                // [I64] -> [F64]
                stack.pop(ValueType.I64);
                stack.push(ValueType.F64);
                break;
            case I64_EXTEND_I32_S:
            case I64_EXTEND_I32_U:
            case I64_LOAD16_S:
            case I64_LOAD16_U:
            case I64_LOAD32_S:
            case I64_LOAD32_U:
            case I64_LOAD8_S:
            case I64_LOAD8_U:
            case I64_LOAD:
                // [I32] -> [I64]
                stack.pop(ValueType.I32);
                stack.push(ValueType.I64);
                break;
            case I64_TRUNC_F32_S:
            case I64_TRUNC_F32_U:
            case I64_TRUNC_SAT_F32_S:
            case I64_TRUNC_SAT_F32_U:
                // [F32] -> [I64]
                stack.pop(ValueType.F32);
                stack.push(ValueType.I64);
                break;
            case F64_CONVERT_I32_S:
            case F64_CONVERT_I32_U:
            case F64_LOAD:
                // [I32] -> [F64]
                stack.pop(ValueType.I32);
                stack.push(ValueType.F64);
                break;
            case F64_PROMOTE_F32:
                // [F32] -> [F64]
                stack.pop(ValueType.F32);
                stack.push(ValueType.F64);
                break;
            case I64_ADD:
            case I64_AND:
            case I64_DIV_S:
            case I64_DIV_U:
            case I64_MUL:
            case I64_OR:
            case I64_REM_S:
            case I64_REM_U:
            case I64_ROTL:
            case I64_ROTR:
            case I64_SHL:
            case I64_SHR_S:
            case I64_SHR_U:
            case I64_SUB:
            case I64_XOR:
                // [I64 I64] -> [I64]
                stack.pop(ValueType.I64);
                stack.pop(ValueType.I64);
                stack.push(ValueType.I64);
                break;
            case F64_ADD:
            case F64_COPYSIGN:
            case F64_DIV:
            case F64_MAX:
            case F64_MIN:
            case F64_MUL:
            case F64_SUB:
                // [F64 F64] -> [F64]
                stack.pop(ValueType.F64);
                stack.pop(ValueType.F64);
                stack.push(ValueType.F64);
                break;
            case I32_STORE:
            case I32_STORE8:
            case I32_STORE16:
                // [I32 I32] -> []
                stack.pop(ValueType.I32);
                stack.pop(ValueType.I32);
                break;
            case F32_STORE:
                // [I32 F32] -> []
                stack.pop(ValueType.F32);
                stack.pop(ValueType.I32);
                break;
            case I64_STORE:
            case I64_STORE8:
            case I64_STORE16:
            case I64_STORE32:
                // [I32 I64] -> []
                stack.pop(ValueType.I64);
                stack.pop(ValueType.I32);
                break;
            case F64_STORE:
                // [I32 F64] -> []
                stack.pop(ValueType.F64);
                stack.pop(ValueType.I32);
                break;
            case I32_CONST:
            case MEMORY_SIZE:
            case TABLE_SIZE:
                // [] -> [I32]
                stack.push(ValueType.I32);
                break;
            case F32_CONST:
                // [] -> [F32]
                stack.push(ValueType.F32);
                break;
            case I64_CONST:
                // [] -> [I64]
                stack.push(ValueType.I64);
                break;
            case F64_CONST:
                // [] -> [F64]
                stack.push(ValueType.F64);
                break;
            case SELECT:
            case SELECT_T:
                // [t t I32] -> [t]
                stack.pop(ValueType.I32);
                var selectType = stack.peek();
                stack.pop(selectType);
                stack.pop(selectType);
                stack.push(selectType);
                break;
            case DATA_DROP:
            case ELEM_DROP:
                // [] -> []
                break;
            case DROP:
            case GLOBAL_SET:
            case LOCAL_SET:
                // [t] -> []
                stack.pop(stack.peek());
                break;
            case LOCAL_TEE:
                // [t] -> [t]
                var teeType = stack.peek();
                stack.pop(teeType);
                stack.push(teeType);
                break;
            case REF_FUNC:
                // [] -> [ref]
                stack.push(ValueType.FuncRef);
                break;
            case REF_NULL:
                // [] -> [ref]
                stack.push(ValueType.refTypeForId((int) ins.operand(0)));
                break;
            case REF_IS_NULL:
                // [ref] -> [I32]
                stack.popRef();
                stack.push(ValueType.I32);
                break;
            case MEMORY_COPY:
            case MEMORY_FILL:
            case MEMORY_INIT:
            case TABLE_COPY:
            case TABLE_INIT:
                // [I32 I32 I32] -> []
                stack.pop(ValueType.I32);
                stack.pop(ValueType.I32);
                stack.pop(ValueType.I32);
                break;
            case TABLE_FILL:
                // [I32 ref I32] -> []
                stack.pop(ValueType.I32);
                stack.pop(stack.peek());
                stack.pop(ValueType.I32);
                break;
            case TABLE_GET:
                // [I32] -> [ref]
                stack.pop(ValueType.I32);
                stack.push(tableTypes.get((int) ins.operand(0)));
                break;
            case TABLE_GROW:
                // [ref I32] -> [I32]
                stack.pop(ValueType.I32);
                stack.pop(tableTypes.get((int) ins.operand(0)));
                stack.push(ValueType.I32);
                break;
            case TABLE_SET:
                // [I32 ref] -> []
                stack.pop(tableTypes.get((int) ins.operand(0)));
                stack.pop(ValueType.I32);
                break;
            case CALL:
                // [p*] -> [r*]
                updateStack(functionTypes.get((int) ins.operand(0)));
                break;
            case CALL_INDIRECT:
                // [p* I32] -> [r*]
                stack.pop(ValueType.I32);
                updateStack(module.typeSection().getType((int) ins.operand(0)));
                break;
            case LOCAL_GET:
                // [] -> [t]
                stack.push(localType(functionType, body, (int) ins.operand(0)));
                break;
            case GLOBAL_GET:
                // [] -> [t]
                ValueType type = globalTypes.get((int) ins.operand(0));
                stack.push(type);
                break;
            default:
                throw new IllegalArgumentException("Unhandled opcode: " + ins.opcode());
        }
    }

    private void updateStack(FunctionType functionType) {
        for (ValueType type : reversed(functionType.params())) {
            stack.pop(type);
        }
        for (ValueType type : functionType.returns()) {
            stack.push(type);
        }
    }

    private FunctionType blockType(Instruction ins) {
        var typeId = (int) ins.operand(0);
        if (typeId == 0x40) {
            return FunctionType.empty();
        }
        if (ValueType.isValid(typeId)) {
            return FunctionType.returning(ValueType.forId(typeId));
        }
        return module.typeSection().getType(typeId);
    }

    private static List<ValueType> getGlobalTypes(Module module) {
        var importedGlobals =
                module.importSection().stream()
                        .filter(GlobalImport.class::isInstance)
                        .map(GlobalImport.class::cast)
                        .map(GlobalImport::type);

        var globals = Stream.of(module.globalSection().globals()).map(Global::valueType);

        return Stream.concat(importedGlobals, globals).collect(toUnmodifiableList());
    }

    private static List<FunctionType> getFunctionTypes(Module module) {
        var importedFunctions =
                module.importSection().stream()
                        .filter(FunctionImport.class::isInstance)
                        .map(FunctionImport.class::cast)
                        .map(function -> module.typeSection().types()[function.typeIndex()]);

        var functions = module.functionSection();
        var moduleFunctions =
                IntStream.range(0, functions.functionCount())
                        .mapToObj(i -> functions.getFunctionType(i, module.typeSection()));

        return Stream.concat(importedFunctions, moduleFunctions).collect(toUnmodifiableList());
    }

    private static List<ValueType> getTableTypes(Module module) {
        var importedTables =
                module.importSection().stream()
                        .filter(TableImport.class::isInstance)
                        .map(TableImport.class::cast)
                        .map(TableImport::entryType);

        var tables = module.tableSection();
        var moduleTables =
                IntStream.range(0, tables.tableCount())
                        .mapToObj(tables::getTable)
                        .map(Table::elementType);

        return Stream.concat(importedTables, moduleTables).collect(toUnmodifiableList());
    }

    private static <T> List<T> reversed(List<T> list) {
        if (list.size() <= 1) {
            return list;
        }
        List<T> reversed = new ArrayList<>(list);
        reverse(reversed);
        return reversed;
    }
}
