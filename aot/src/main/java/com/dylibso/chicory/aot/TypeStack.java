package com.dylibso.chicory.aot;

import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayDeque;
import java.util.Deque;

final class TypeStack {

    private final Deque<Deque<ValueType>> types = new ArrayDeque<>();
    private final Deque<Deque<ValueType>> restore = new ArrayDeque<>();

    public TypeStack() {
        this.types.push(new ArrayDeque<>());
    }

    public ValueType peek() {
        return types().getFirst();
    }

    public void push(ValueType type) {
        types().push(type);
    }

    public void pop(ValueType expected) {
        var actual = types().pop();
        if (expected != actual) {
            throw new IllegalArgumentException("Expected type " + expected + " <> " + actual);
        }
    }

    public void popRef() {
        var actual = types().pop();
        if (actual != ValueType.FuncRef && actual != ValueType.ExternRef) {
            throw new IllegalArgumentException("Expected reference type <> " + actual);
        }
    }

    public void pushTypes() {
        types.push(new ArrayDeque<>(types()));
    }

    public void popTypes() {
        types.pop();
    }

    public void enterScope(FunctionType scopeType) {
        // restored stack when exiting "polymorphic" blocks after unconditional control transfer
        Deque<ValueType> stack = new ArrayDeque<>(types());
        for (int i = 0; i < scopeType.params().size(); i++) {
            stack.pop();
        }
        for (ValueType type : scopeType.returns()) {
            stack.push(type);
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

    public Deque<ValueType> types() {
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
