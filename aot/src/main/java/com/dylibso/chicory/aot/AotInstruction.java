package com.dylibso.chicory.aot;

import static java.util.Objects.requireNonNull;

import com.dylibso.chicory.wasm.types.ValueType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

final class AotInstruction {
    private static final long[] EMPTY = new long[0];

    private final AotOpCode opcode;
    private final long[] operands;
    private final List<ValueType> stack;
    private final int minStack;

    public AotInstruction(AotOpCode opcode, long operand, List<ValueType> stack, int minStack) {
        this(opcode, new long[] {operand}, stack, minStack);
    }

    public AotInstruction(AotOpCode opcode, long[] operands, List<ValueType> stack, int minStack) {
        this.opcode = requireNonNull(opcode);
        this.operands = operands;
        this.stack = List.copyOf(stack);
        this.minStack = minStack;
    }

    public AotOpCode opcode() {
        return opcode;
    }

    public LongStream operands() {
        return Arrays.stream(operands);
    }

    public int operandCount() {
        return operands.length;
    }

    public long operand(int index) {
        return operands[index];
    }

    public List<ValueType> stack() {
        return stack;
    }

    public int minStack() {
        return minStack;
    }

    @Override
    public String toString() {
        if (operands.length == 0) {
            return opcode.toString();
        }
        if (operands.length == 1) {
            return opcode + " " + operands[0];
        }
        return opcode + " " + Arrays.toString(operands);
    }

    public long[] labelTargets() {
        switch (opcode) {
            case GOTO:
            case IFEQ:
            case IFNE:
            case SWITCH:
                return operands;
            default:
                return EMPTY;
        }
    }
}
