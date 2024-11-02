package com.dylibso.chicory.aot;

import static com.dylibso.chicory.aot.AotUtil.slotCount;

import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayList;
import java.util.List;

/**
 * Class for tracking context relevant to compiling a single function
 */
final class AotContext {

    private final String internalClassName;
    private final String internalContextClassName;
    private final List<ValueType> globalTypes;
    private final List<FunctionType> functionTypes;
    private final FunctionType[] types;
    private final boolean huge;
    private final int funcId;
    private final FunctionType type;
    private final FunctionBody body;
    private final List<Integer> slots;
    private final int contextSlot;
    private final int memorySlot;
    private final int instanceSlot;
    private final int tempSlot;

    public AotContext(
            String internalClassName,
            String internalContextClassName,
            List<ValueType> globalTypes,
            List<FunctionType> functionTypes,
            FunctionType[] types,
            List<ValueType> paramTypes,
            boolean huge,
            int funcId,
            FunctionType type,
            FunctionBody body) {
        this.internalClassName = internalClassName;
        this.internalContextClassName = internalContextClassName;
        this.globalTypes = globalTypes;
        this.functionTypes = functionTypes;
        this.types = types;
        this.huge = huge;
        this.funcId = funcId;
        this.type = type;
        this.body = body;

        // compute JVM slot indices for WASM locals
        List<Integer> slots = new ArrayList<>();
        int slot = 0;

        // WASM arguments
        for (ValueType param : paramTypes) {
            slots.add(slot);
            slot += slotCount(param);
        }

        // context argument
        if (huge) {
            this.contextSlot = slot;
            slot++;
        } else {
            this.contextSlot = -1;
        }

        // extra arguments
        this.memorySlot = slot;
        slot++;
        this.instanceSlot = slot;
        slot++;

        // WASM locals
        if (!huge) {
            for (ValueType local : body.localTypes()) {
                slots.add(slot);
                slot += slotCount(local);
            }
        }

        this.slots = List.copyOf(slots);
        this.tempSlot = slot;
    }

    public String internalClassName() {
        return internalClassName;
    }

    public String internalContextClassName() {
        return internalContextClassName;
    }

    public List<ValueType> globalTypes() {
        return globalTypes;
    }

    public List<FunctionType> functionTypes() {
        return functionTypes;
    }

    public FunctionType[] types() {
        return types;
    }

    public boolean huge() {
        return huge;
    }

    public int funcId() {
        return funcId;
    }

    public FunctionType getType() {
        return type;
    }

    public FunctionBody getBody() {
        return body;
    }

    public int localSlotIndex(int localIndex) {
        return slots.get(localIndex);
    }

    public int contextSlot() {
        return contextSlot;
    }

    public int memorySlot() {
        return memorySlot;
    }

    public int instanceSlot() {
        return instanceSlot;
    }

    public int tempSlot() {
        return tempSlot;
    }

    public AotContext copyForInner(List<ValueType> paramTypes) {
        return new AotContext(
                internalClassName,
                internalContextClassName,
                globalTypes,
                functionTypes,
                types,
                paramTypes,
                huge,
                funcId,
                type,
                body);
    }
}
