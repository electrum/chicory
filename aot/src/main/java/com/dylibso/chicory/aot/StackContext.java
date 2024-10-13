package com.dylibso.chicory.aot;

import static com.dylibso.chicory.aot.AotUtil.jvmType;
import static com.dylibso.chicory.aot.AotUtil.stackSize;

import com.dylibso.chicory.aot.AotUtil.StackSize;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

final class StackContext {

    private final Deque<Deque<StackSize>> stackSizesStack = new ArrayDeque<>();
    private final Map<Instruction, Integer> scopeStackSize = new HashMap<>();
    private final Deque<Deque<StackSize>> restoreStackSize = new ArrayDeque<>();

    public StackContext() {
        this.stackSizesStack.push(new ArrayDeque<>());
    }

    public StackSize peekStackSize() {
        return stackSizes().getFirst();
    }

    public void pushStackSize(StackSize size) {
        stackSizes().push(size);
    }

    public void popStackSize(StackSize expected) {
        StackSize actual = stackSizes().pop();
        if (expected != actual) {
            throw new IllegalArgumentException("Expected stack size " + expected + " <> " + actual);
        }
    }

    public void pushStackSizesStack() {
        stackSizesStack.push(new ArrayDeque<>(stackSizes()));
    }

    public void popStackSizesStack() {
        stackSizesStack.pop();
    }

    public void enterScope(Instruction scope, FunctionType scopeType) {
        scopeStackSize.put(scope, stackSizes().size());

        // stack sizes when exiting "polymorphic" blocks after unconditional control transfer
        Deque<StackSize> stack = new ArrayDeque<>(stackSizes());
        for (int i = 0; i < scopeType.params().size(); i++) {
            stack.pop();
        }
        for (ValueType type : scopeType.returns()) {
            stack.push(stackSize(jvmType(type)));
        }
        restoreStackSize.push(stack);
    }

    public void exitScope(Instruction scope) {
        scopeStackSize.remove(scope);
        restoreStackSize.pop();
    }

    public void scopeRestoreStackSize() {
        stackSizesStack.pop();
        stackSizesStack.push(restoreStackSize.getFirst());
    }

    public Deque<StackSize> stackSizes() {
        return stackSizesStack.getFirst();
    }

    public void verifyEmpty() {
        if (stackSizesStack.size() != 1) {
            throw new RuntimeException("Bad stack sizes stack: " + stackSizesStack);
        }
        if (!stackSizes().isEmpty()) {
            throw new RuntimeException("Stack sizes not empty: " + stackSizes());
        }
    }
}
