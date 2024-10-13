package com.dylibso.chicory.aot;

import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayDeque;
import java.util.Deque;

final class StackContext {

    private final Deque<Deque<StackType>> types = new ArrayDeque<>();
    private final Deque<Deque<StackType>> restore = new ArrayDeque<>();

    public StackContext() {
        this.types.push(new ArrayDeque<>());
    }

    public StackType peek() {
        return types().getFirst();
    }

    public void push(StackType type) {
        types().push(type);
    }

    public void pop(StackType expected) {
        var actual = types().pop();
        if (expected != actual) {
            throw new IllegalArgumentException("Expected type " + expected + " <> " + actual);
        }
    }

    public void pushTypes() {
        types.push(new ArrayDeque<>(types()));
    }

    public void popTypes() {
        types.pop();
    }

    public void enterScope(FunctionType scopeType) {
        // stack sizes when exiting "polymorphic" blocks after unconditional control transfer
        Deque<StackType> stack = new ArrayDeque<>(types());
        for (int i = 0; i < scopeType.params().size(); i++) {
            stack.pop();
        }
        for (ValueType type : scopeType.returns()) {
            stack.push(StackType.of(type));
        }
        restore.push(stack);
    }

    public void exitScope() {
        restore.pop();
    }

    public void scopeRestore() {
        types.pop();
        types.push(restore.getFirst());
    }

    public Deque<StackType> types() {
        return types.getFirst();
    }

    public void verifyEmpty() {
        if (types.size() != 1) {
            throw new RuntimeException("Bad types stack: " + types);
        }
        if (!types().isEmpty()) {
            throw new RuntimeException("Types not empty: " + types());
        }
    }
}
