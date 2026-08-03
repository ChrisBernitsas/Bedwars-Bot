package com.bedwarsbot.control;

import java.util.ArrayList;
import java.util.List;

public final class InputFrame {
    public static final int NO_HOTBAR_SELECTION = -1;

    private static final InputFrame NEUTRAL = new Builder().build();

    private final boolean forward;
    private final boolean backward;
    private final boolean left;
    private final boolean right;
    private final boolean jump;
    private final boolean sneak;
    private final boolean sprint;
    private final int hotbarSlot;

    private InputFrame(Builder builder) {
        this.forward = builder.forward;
        this.backward = builder.backward;
        this.left = builder.left;
        this.right = builder.right;
        this.jump = builder.jump;
        this.sneak = builder.sneak;
        this.sprint = builder.sprint;
        this.hotbarSlot = builder.hotbarSlot;
    }

    public static InputFrame neutral() {
        return NEUTRAL;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(InputFrame source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        return new Builder(source);
    }

    public boolean isForward() {
        return forward;
    }

    public boolean isBackward() {
        return backward;
    }

    public boolean isLeft() {
        return left;
    }

    public boolean isRight() {
        return right;
    }

    public boolean isJump() {
        return jump;
    }

    public boolean isSneak() {
        return sneak;
    }

    public boolean isSprint() {
        return sprint;
    }

    public int getHotbarSlot() {
        return hotbarSlot;
    }

    public boolean isNeutral() {
        return !forward
            && !backward
            && !left
            && !right
            && !jump
            && !sneak
            && !sprint
            && hotbarSlot == NO_HOTBAR_SELECTION;
    }

    public InputFrame withoutHotbarSelection() {
        if (hotbarSlot == NO_HOTBAR_SELECTION) {
            return this;
        }
        return builder(this).hotbarSlot(NO_HOTBAR_SELECTION).build();
    }

    public String toCompactString() {
        List<String> active = new ArrayList<String>();
        if (forward) {
            active.add("forward");
        }
        if (backward) {
            active.add("backward");
        }
        if (left) {
            active.add("left");
        }
        if (right) {
            active.add("right");
        }
        if (jump) {
            active.add("jump");
        }
        if (sneak) {
            active.add("sneak");
        }
        if (sprint) {
            active.add("sprint");
        }
        if (hotbarSlot != NO_HOTBAR_SELECTION) {
            active.add("hotbar=" + (hotbarSlot + 1));
        }
        return active.isEmpty() ? "none" : join(active);
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) {
                joined.append('+');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InputFrame)) {
            return false;
        }
        InputFrame that = (InputFrame) other;
        return forward == that.forward
            && backward == that.backward
            && left == that.left
            && right == that.right
            && jump == that.jump
            && sneak == that.sneak
            && sprint == that.sprint
            && hotbarSlot == that.hotbarSlot;
    }

    @Override
    public int hashCode() {
        int result = forward ? 1 : 0;
        result = 31 * result + (backward ? 1 : 0);
        result = 31 * result + (left ? 1 : 0);
        result = 31 * result + (right ? 1 : 0);
        result = 31 * result + (jump ? 1 : 0);
        result = 31 * result + (sneak ? 1 : 0);
        result = 31 * result + (sprint ? 1 : 0);
        result = 31 * result + hotbarSlot;
        return result;
    }

    @Override
    public String toString() {
        return "InputFrame{" + toCompactString() + '}';
    }

    public static final class Builder {
        private boolean forward;
        private boolean backward;
        private boolean left;
        private boolean right;
        private boolean jump;
        private boolean sneak;
        private boolean sprint;
        private int hotbarSlot = NO_HOTBAR_SELECTION;

        private Builder() {
        }

        private Builder(InputFrame source) {
            this.forward = source.forward;
            this.backward = source.backward;
            this.left = source.left;
            this.right = source.right;
            this.jump = source.jump;
            this.sneak = source.sneak;
            this.sprint = source.sprint;
            this.hotbarSlot = source.hotbarSlot;
        }

        public Builder forward(boolean value) {
            this.forward = value;
            return this;
        }

        public Builder backward(boolean value) {
            this.backward = value;
            return this;
        }

        public Builder left(boolean value) {
            this.left = value;
            return this;
        }

        public Builder right(boolean value) {
            this.right = value;
            return this;
        }

        public Builder jump(boolean value) {
            this.jump = value;
            return this;
        }

        public Builder sneak(boolean value) {
            this.sneak = value;
            return this;
        }

        public Builder sprint(boolean value) {
            this.sprint = value;
            return this;
        }

        public Builder hotbarSlot(int value) {
            this.hotbarSlot = value;
            return this;
        }

        public InputFrame build() {
            return new InputFrame(this);
        }
    }
}
