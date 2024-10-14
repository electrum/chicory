package com.dylibso.chicory.aot;

import static java.lang.Math.min;
import static java.util.stream.Collectors.toList;

import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

final class TypeStack {

    private final Deque<Deque<ValueType>> types = new ArrayDeque<>();
    private final Deque<Deque<ValueType>> restore = new ArrayDeque<>();
    private Deque<ValueType> startingTypes;
    private int minSize;

    public TypeStack() {
        this.types.push(new ArrayDeque<>());
    }

    public ValueType peek() {
        return types().element();
    }

    public void push(ValueType type) {
        types().push(type);
        System.out.println("  push(" + type + ")");
    }

    public void pop(ValueType expected) {
        var actual = pop();
        if (expected != actual) {
            throw new IllegalArgumentException("Expected type " + expected + " <> " + actual);
        }
    }

    public void popRef() {
        var actual = pop();
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
        types.push(restore.element());
    }

    public Deque<ValueType> types() {
        return types.element();
    }

    public void verifyEmpty() {
        if (types.size() != 1) {
            throw new RuntimeException("Bad types stack: " + types);
        }
        if (!types().isEmpty()) {
            throw new RuntimeException("Types not empty: " + types());
        }
    }

    private ValueType pop() {
        var type = types().pop();
        minSize = min(minSize, types().size());
        System.out.println("  pop(" + type + ") min=" + minSize);
        return type;
    }

    public void reset() {
        startingTypes = new ArrayDeque<>(types());
        minSize = startingTypes.size();
    }

    public List<ValueType> consumed() {
        int size = startingTypes.size() - minSize;
        return startingTypes.stream().limit(size).collect(toList());
    }

    public List<ValueType> produced() {
        int size = types().size() - minSize;
        return types().stream().limit(size).collect(toList());
    }
}
