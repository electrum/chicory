package com.dylibso.chicory.aot;

import static com.dylibso.chicory.aot.AotUtil.reversed;
import static com.dylibso.chicory.aot.MethodSplitter.SplitResult.continueResult;
import static com.dylibso.chicory.aot.MethodSplitter.SplitResult.gotoResult;
import static com.dylibso.chicory.aot.MethodSplitter.SplitResult.returnResult;

import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.types.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

final class MethodSplitter {

    private MethodSplitter() {}

    public static List<Split> computeSplits(List<AotInstruction> instructions) {
        var ranges = computeSplitRanges(instructions);
        List<Split> splits = new ArrayList<>();
        for (var range : ranges) {
            Split split = computeSplit(instructions, range);
            if (!split.results().isEmpty()) {
                splits.add(split);
            }
        }
        return splits;
    }

    private static Split computeSplit(List<AotInstruction> instructions, Range range) {
        // using a label as the start makes the stack calculation easier
        var start = instructions.get(range.start());
        if (start.opcode() != AotOpCode.LABEL) {
            throw new ChicoryException("Unexpected split start: " + start);
        }
        var end = instructions.get(range.end() - 1);

        // find the minimum stack depth for the split range
        int minStack =
                instructions.subList(range.start(), range.end()).stream()
                        .mapToInt(AotInstruction::minStack)
                        .min()
                        .orElseThrow();

        // starting stack for the split method
        var params = start.stack().subList(0, start.stack().size() - minStack);

        Set<SplitResult> results = new LinkedHashSet<>();

        // check if execution continues after the split
        switch (end.opcode()) {
            case TRAP:
            case RETURN:
            case GOTO:
            case SWITCH:
                break;
            default:
                var types = end.stack().subList(0, end.stack().size() - minStack);
                results.add(continueResult(reversed(types)));
        }

        // extract local label targets
        Set<Long> localTargets = new HashSet<>();
        for (var ins : instructions.subList(range.start(), range.end())) {
            if (ins.opcode() == AotOpCode.LABEL) {
                localTargets.add(ins.operand(0));
            }
        }

        // record all exit points
        for (var ins : instructions.subList(range.start(), range.end())) {
            if (ins.opcode() == AotOpCode.RETURN) {
                results.add(returnResult());
            } else {
                for (long target : ins.labelTargets()) {
                    if (!localTargets.contains(target)) {
                        var types = ins.stack().subList(0, ins.stack().size() - minStack);
                        results.add(gotoResult(reversed(types), target));
                    }
                }
            }
        }

        return new Split(range.start(), range.end(), reversed(params), List.copyOf(results));
    }

    private static List<Range> computeSplitRanges(List<AotInstruction> instructions) {
        Map<Long, Integer> labelIndexes = new HashMap<>();
        Map<Long, Set<Integer>> labelSources = new HashMap<>();
        Set<Long> switchLabels = new HashSet<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() == AotOpCode.LABEL) {
                labelIndexes.put(ins.operand(0), idx);
                labelSources.putIfAbsent(ins.operand(0), new HashSet<>());
            } else if (ins.opcode() == AotOpCode.SWITCH) {
                for (long label : ins.labelTargets()) {
                    switchLabels.add(label);
                }
            }
            for (long label : ins.labelTargets()) {
                labelSources.computeIfAbsent(label, x -> new HashSet<>()).add(idx);
            }
        }

        List<Range> ranges = new ArrayList<>();
        int splitStart = -1;
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() == AotOpCode.LABEL && switchLabels.contains(ins.operand(0))) {
                if (splitStart >= 0) {
                    ranges.add(new Range(splitStart, idx));
                    //System.out.println("</SPLIT>");
                }
                splitStart = idx;
                //System.out.println("<SPLIT>");
            } else if (ins.opcode() == AotOpCode.LABEL && splitStart >= 0) {
                int start = splitStart;
                int current = idx;
                Predicate<Integer> outside = x -> (x < start) || (x >= current);
                if (labelSources.get(ins.operand(0)).stream().anyMatch(outside)) {
                    ranges.add(new Range(splitStart, idx));
                    splitStart = -1;
                    //System.out.println("</SPLIT>");
                }
            }
            //AotCompiler.printInstruction(ins);
        }
        //System.out.println();
        return ranges;
    }

    public static class Split {
        private final int start;
        private final int end;
        private final List<ValueType> params;
        private final List<SplitResult> results;

        public Split(int start, int end, List<ValueType> params, List<SplitResult> results) {
            this.start = start;
            this.end = end;
            this.params = params;
            this.results = results;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public List<ValueType> params() {
            return params;
        }

        public List<SplitResult> results() {
            return results;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", "Split{", "}")
                    .add("range=[" + start + ", " + end + "]")
                    .add("params=" + params)
                    .add("results=" + results)
                    .toString();
        }
    }

    public static final class SplitResult {
        private final ResultType type;
        private final List<ValueType> types;
        private final long target;

        public static SplitResult returnResult() {
            return new SplitResult(ResultType.RETURN, List.of(), -1);
        }

        public static SplitResult continueResult(List<ValueType> types) {
            return new SplitResult(ResultType.CONTINUE, types, -1);
        }

        public static SplitResult gotoResult(List<ValueType> types, long target) {
            return new SplitResult(ResultType.GOTO, types, target);
        }

        private SplitResult(ResultType type, List<ValueType> types, long target) {
            this.type = type;
            this.types = types;
            this.target = target;
        }

        public ResultType type() {
            return type;
        }

        public List<ValueType> types() {
            return types;
        }

        public long target() {
            return target;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SplitResult)) {
                return false;
            }
            SplitResult x = (SplitResult) o;
            return target == x.target && type == x.type && types.equals(x.types);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, types, target);
        }

        @Override
        public String toString() {
            var joiner = new StringJoiner(", ", "{", "}").add(type.name());
            if (type != ResultType.RETURN) {
                joiner.add(types.toString());
            }
            if (type == ResultType.GOTO) {
                joiner.add("target=" + target);
            }
            return joiner.toString();
        }
    }

    public enum ResultType {
        RETURN,
        CONTINUE,
        GOTO
    }

    private static final class Range {
        private final int start;
        private final int end;

        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        @Override
        public String toString() {
            return "[" + start + ", " + end + "]";
        }
    }
}
