package com.dylibso.chicory.aot;

import com.dylibso.chicory.wasm.types.ValueType;

enum StackType {
    I32,
    I64,
    F32,
    F64,
    REF;

    public static StackType of(ValueType type) {
        switch (type) {
            case I32:
                return I32;
            case I64:
                return I64;
            case F32:
                return F32;
            case F64:
                return F64;
            case FuncRef:
            case ExternRef:
                return REF;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }
}
