package com.argorice.epicysm.client.convert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The part of molang an animation file leans on: arithmetic, comparisons,
 * the ternary, math.* and the handful of queries a model animates by
 * (anim_time, life_time, ground_speed, ...). Compiled once into a tree,
 * evaluated every frame against a {@link Context} that answers the
 * queries.
 *
 * Variables (v.*, t.*) live for one evaluation: assigned in a statement
 * list ("v.x = ...; return ...;"), read back in the same list, and 0
 * otherwise, which is what an unset molang variable is. Anything this
 * evaluator does not know - a query it never heard of, a control-flow
 * keyword, another mod's namespace - is 0 as well, so an expression never
 * fails at runtime; it can only be worth nothing.
 */
public final class Molang {
    /** Answers the queries of the entity being animated. Names are lower case without the "query." prefix. */
    public interface Context {
        float query(String name);
    }

    /** A compiled expression. */
    public interface Expr {
        float eval(Context context, @Nullable Map<String, Float> vars);
    }

    private static final Expr ZERO = (context, vars) -> 0.0F;

    private final String text;
    private int position;

    private Molang(String text) {
        this.text = text;
    }

    /** A number as an expression. */
    public static Expr constant(float value) {
        return (context, vars) -> value;
    }

    /**
     * Compiles the text; a constant expression is folded to its number.
     * Returns null for text this evaluator cannot read.
     */
    @Nullable
    public static Expr compile(@Nullable String text) {
        if (text == null) {
            return null;
        }

        String trimmed = text.trim();

        if (trimmed.isEmpty()) {
            return constant(0.0F);
        }

        Molang parser = new Molang(trimmed);

        try {
            Expr expr = parser.block();
            parser.skipWhitespace();
            return parser.position >= parser.text.length() ? expr : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the expression needs nothing from the context. */
    public static boolean isConstant(Expr expr) {
        return expr instanceof Const;
    }

    private record Const(float value) implements Expr {
        @Override
        public float eval(Context context, @Nullable Map<String, Float> vars) {
            return this.value;
        }
    }

    /* ------------------------------------------------------------------
     * Statements
     * ------------------------------------------------------------------ */

    private Expr block() {
        List<Expr> statements = new ArrayList<>();
        List<String> assignTo = new ArrayList<>();
        int returnAt = -1;

        while (true) {
            skipWhitespace();

            if (this.position >= this.text.length()) {
                break;
            }

            if (peek() == ';') {
                this.position++;
                continue;
            }

            if (returnAt >= 0) {
                // Nothing after a return matters; skip it.
                this.position = this.text.length();
                break;
            }

            if (startsWithWord("return")) {
                this.position += 6;
                statements.add(ternary());
                assignTo.add(null);
                returnAt = statements.size() - 1;
            } else {
                int start = this.position;
                String target = assignmentTarget();

                if (target != null) {
                    statements.add(ternary());
                    assignTo.add(target);
                } else {
                    this.position = start;
                    statements.add(ternary());
                    assignTo.add(null);
                }
            }

            skipWhitespace();

            if (peek() == ';') {
                this.position++;
            } else if (this.position < this.text.length()) {
                throw new IllegalArgumentException("expected ;");
            }
        }

        if (statements.isEmpty()) {
            return constant(0.0F);
        }

        if (statements.size() == 1 && assignTo.get(0) == null) {
            Expr only = statements.get(0);
            return fold(only);
        }

        Expr[] exprs = statements.toArray(new Expr[0]);
        String[] targets = assignTo.toArray(new String[0]);
        int resultAt = returnAt >= 0 ? returnAt : exprs.length - 1;

        return (context, vars) -> {
            Map<String, Float> locals = vars != null ? vars : new HashMap<>();
            float result = 0.0F;

            for (int i = 0; i < exprs.length; i++) {
                float value = exprs[i].eval(context, locals);

                if (targets[i] != null) {
                    locals.put(targets[i], value);
                }

                if (i == resultAt) {
                    result = value;
                }
            }

            return result;
        };
    }

    /** "v.name =" (not "==") at the cursor: the name, with the cursor after "="; else null. */
    @Nullable
    private String assignmentTarget() {
        skipWhitespace();
        int start = this.position;
        String name = identifier();

        if (name == null) {
            return null;
        }

        skipWhitespace();

        if (peek() == '=' && peekAt(1) != '=') {
            this.position++;
            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.startsWith("v.") || lower.startsWith("variable.") || lower.startsWith("t.") || lower.startsWith("temp.")) {
                return variableKey(lower);
            }
        }

        this.position = start;
        return null;
    }

    private static String variableKey(String lower) {
        int dot = lower.indexOf('.');
        return dot < 0 ? lower : lower.substring(dot + 1);
    }

    /* ------------------------------------------------------------------
     * Expressions, loosest binding first
     * ------------------------------------------------------------------ */

    private Expr ternary() {
        Expr condition = nullCoalesce();
        skipWhitespace();

        if (peek() == '?') {
            this.position++;
            Expr whenTrue = ternary();
            skipWhitespace();

            if (peek() != ':') {
                throw new IllegalArgumentException("expected :");
            }

            this.position++;
            Expr whenFalse = ternary();
            return fold((context, vars) -> condition.eval(context, vars) != 0.0F
                    ? whenTrue.eval(context, vars) : whenFalse.eval(context, vars));
        }

        return condition;
    }

    private Expr nullCoalesce() {
        Expr left = or();
        skipWhitespace();

        while (peek() == '?' && peekAt(1) == '?') {
            this.position += 2;
            Expr right = or();
            Expr l = left;
            // Nothing here is ever null; the left side always wins.
            left = (context, vars) -> l.eval(context, vars);
            skipWhitespace();
        }

        return left;
    }

    private Expr or() {
        Expr left = and();

        while (true) {
            skipWhitespace();

            if (peek() == '|' && peekAt(1) == '|') {
                this.position += 2;
                Expr right = and();
                Expr l = left;
                left = fold((context, vars) -> (l.eval(context, vars) != 0.0F || right.eval(context, vars) != 0.0F) ? 1.0F : 0.0F);
            } else {
                return left;
            }
        }
    }

    private Expr and() {
        Expr left = equality();

        while (true) {
            skipWhitespace();

            if (peek() == '&' && peekAt(1) == '&') {
                this.position += 2;
                Expr right = equality();
                Expr l = left;
                left = fold((context, vars) -> (l.eval(context, vars) != 0.0F && right.eval(context, vars) != 0.0F) ? 1.0F : 0.0F);
            } else {
                return left;
            }
        }
    }

    private Expr equality() {
        Expr left = comparison();

        while (true) {
            skipWhitespace();
            char c = peek();
            char d = peekAt(1);

            if (c == '=' && d == '=') {
                this.position += 2;
                Expr right = comparison();
                Expr l = left;
                left = fold((context, vars) -> l.eval(context, vars) == right.eval(context, vars) ? 1.0F : 0.0F);
            } else if (c == '!' && d == '=') {
                this.position += 2;
                Expr right = comparison();
                Expr l = left;
                left = fold((context, vars) -> l.eval(context, vars) != right.eval(context, vars) ? 1.0F : 0.0F);
            } else {
                return left;
            }
        }
    }

    private Expr comparison() {
        Expr left = additive();

        while (true) {
            skipWhitespace();
            char c = peek();
            char d = peekAt(1);
            Expr l = left;

            if (c == '<' && d == '=') {
                this.position += 2;
                Expr right = additive();
                left = fold((context, vars) -> l.eval(context, vars) <= right.eval(context, vars) ? 1.0F : 0.0F);
            } else if (c == '>' && d == '=') {
                this.position += 2;
                Expr right = additive();
                left = fold((context, vars) -> l.eval(context, vars) >= right.eval(context, vars) ? 1.0F : 0.0F);
            } else if (c == '<') {
                this.position++;
                Expr right = additive();
                left = fold((context, vars) -> l.eval(context, vars) < right.eval(context, vars) ? 1.0F : 0.0F);
            } else if (c == '>') {
                this.position++;
                Expr right = additive();
                left = fold((context, vars) -> l.eval(context, vars) > right.eval(context, vars) ? 1.0F : 0.0F);
            } else {
                return left;
            }
        }
    }

    private Expr additive() {
        Expr left = multiplicative();

        while (true) {
            skipWhitespace();
            char c = peek();
            Expr l = left;

            if (c == '+') {
                this.position++;
                Expr right = multiplicative();
                left = fold((context, vars) -> l.eval(context, vars) + right.eval(context, vars));
            } else if (c == '-') {
                this.position++;
                Expr right = multiplicative();
                left = fold((context, vars) -> l.eval(context, vars) - right.eval(context, vars));
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();

        while (true) {
            skipWhitespace();
            char c = peek();
            Expr l = left;

            if (c == '*') {
                this.position++;
                Expr right = unary();
                left = fold((context, vars) -> l.eval(context, vars) * right.eval(context, vars));
            } else if (c == '/') {
                this.position++;
                Expr right = unary();
                left = fold((context, vars) -> {
                    float divisor = right.eval(context, vars);
                    return divisor == 0.0F ? 0.0F : l.eval(context, vars) / divisor;
                });
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        skipWhitespace();
        char c = peek();

        if (c == '!') {
            this.position++;
            Expr inner = unary();
            return fold((context, vars) -> inner.eval(context, vars) == 0.0F ? 1.0F : 0.0F);
        }

        if (c == '-') {
            this.position++;
            Expr inner = unary();
            return fold((context, vars) -> -inner.eval(context, vars));
        }

        if (c == '+') {
            this.position++;
            return unary();
        }

        return primary();
    }

    private Expr primary() {
        skipWhitespace();
        char c = peek();

        if (c == '(') {
            this.position++;
            Expr value = ternary();
            skipWhitespace();

            if (peek() != ')') {
                throw new IllegalArgumentException("expected )");
            }

            this.position++;
            return value;
        }

        if (c == '{') {
            // A nested statement block; its value is the block's.
            this.position++;
            int depth = 1;
            int start = this.position;

            while (this.position < this.text.length() && depth > 0) {
                char d = this.text.charAt(this.position);

                if (d == '{') {
                    depth++;
                } else if (d == '}') {
                    depth--;
                }

                this.position++;
            }

            if (depth != 0) {
                throw new IllegalArgumentException("unbalanced {");
            }

            Expr inner = compile(this.text.substring(start, this.position - 1));
            return inner != null ? inner : ZERO;
        }

        if (c == '\'') {
            this.position++;

            while (this.position < this.text.length() && this.text.charAt(this.position) != '\'') {
                this.position++;
            }

            if (this.position >= this.text.length()) {
                throw new IllegalArgumentException("unterminated string");
            }

            this.position++;
            return constant(0.0F);
        }

        if (Character.isDigit(c) || c == '.') {
            int start = this.position;

            while (this.position < this.text.length()) {
                char d = this.text.charAt(this.position);

                if (Character.isDigit(d) || d == '.') {
                    this.position++;
                } else {
                    break;
                }
            }

            // A trailing "f", as in "1.0f", is allowed by some exporters.
            if (peek() == 'f' || peek() == 'F') {
                float value = Float.parseFloat(this.text.substring(start, this.position));
                this.position++;
                return constant(value);
            }

            return constant(Float.parseFloat(this.text.substring(start, this.position)));
        }

        String name = identifier();

        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            skipWhitespace();

            if (peek() == '(') {
                this.position++;
                List<Expr> args = new ArrayList<>();
                skipWhitespace();

                if (peek() != ')') {
                    args.add(ternary());
                    skipWhitespace();

                    while (peek() == ',') {
                        this.position++;
                        args.add(ternary());
                        skipWhitespace();
                    }
                }

                if (peek() != ')') {
                    throw new IllegalArgumentException("expected )");
                }

                this.position++;
                return call(lower, args);
            }

            return symbol(lower);
        }

        throw new IllegalArgumentException("unexpected character");
    }

    /* ------------------------------------------------------------------
     * Names
     * ------------------------------------------------------------------ */

    private Expr symbol(String lower) {
        if (lower.equals("math.pi")) {
            return constant((float) Math.PI);
        }

        if (lower.equals("true")) {
            return constant(1.0F);
        }

        if (lower.equals("false")) {
            return constant(0.0F);
        }

        if (lower.startsWith("q.") || lower.startsWith("query.")) {
            String query = lower.substring(lower.indexOf('.') + 1);
            return (context, vars) -> context.query(query);
        }

        if (lower.startsWith("v.") || lower.startsWith("variable.") || lower.startsWith("t.") || lower.startsWith("temp.")) {
            String key = variableKey(lower);
            return (context, vars) -> {
                Float value = vars != null ? vars.get(key) : null;
                return value != null ? value : 0.0F;
            };
        }

        // context.*, ctrl.*, ysm.*, and anything else: nothing here.
        return ZERO;
    }

    private Expr call(String lower, List<Expr> args) {
        String function = lower.startsWith("math.") ? lower.substring(5) : lower;

        if (lower.startsWith("q.") || lower.startsWith("query.")) {
            // A query with arguments, e.g. q.anim_time(...): read as the plain query.
            String query = lower.substring(lower.indexOf('.') + 1);
            return (context, vars) -> context.query(query);
        }

        if (!lower.startsWith("math.")) {
            return ZERO;
        }

        Expr a = args.size() > 0 ? args.get(0) : ZERO;
        Expr b = args.size() > 1 ? args.get(1) : ZERO;
        Expr c = args.size() > 2 ? args.get(2) : ZERO;

        // Trigonometry in molang works in degrees.
        Expr result = switch (function) {
            case "sin" -> (context, vars) -> (float) Math.sin(Math.toRadians(a.eval(context, vars)));
            case "cos" -> (context, vars) -> (float) Math.cos(Math.toRadians(a.eval(context, vars)));
            case "tan" -> (context, vars) -> (float) Math.tan(Math.toRadians(a.eval(context, vars)));
            case "asin" -> (context, vars) -> (float) Math.toDegrees(Math.asin(clampUnit(a.eval(context, vars))));
            case "acos" -> (context, vars) -> (float) Math.toDegrees(Math.acos(clampUnit(a.eval(context, vars))));
            case "atan" -> (context, vars) -> (float) Math.toDegrees(Math.atan(a.eval(context, vars)));
            case "atan2" -> (context, vars) -> (float) Math.toDegrees(Math.atan2(a.eval(context, vars), b.eval(context, vars)));
            case "abs" -> (context, vars) -> Math.abs(a.eval(context, vars));
            case "sqrt" -> (context, vars) -> (float) Math.sqrt(Math.max(0.0F, a.eval(context, vars)));
            case "pow" -> (context, vars) -> (float) Math.pow(a.eval(context, vars), b.eval(context, vars));
            case "exp" -> (context, vars) -> (float) Math.exp(a.eval(context, vars));
            case "ln" -> (context, vars) -> {
                float x = a.eval(context, vars);
                return x > 0.0F ? (float) Math.log(x) : 0.0F;
            };
            case "floor" -> (context, vars) -> (float) Math.floor(a.eval(context, vars));
            case "ceil" -> (context, vars) -> (float) Math.ceil(a.eval(context, vars));
            case "round" -> (context, vars) -> (float) Math.round(a.eval(context, vars));
            case "trunc" -> (context, vars) -> (float) (long) a.eval(context, vars);
            case "sign" -> (context, vars) -> Math.signum(a.eval(context, vars));
            case "mod" -> (context, vars) -> {
                float divisor = b.eval(context, vars);
                return divisor == 0.0F ? 0.0F : a.eval(context, vars) % divisor;
            };
            case "min" -> (context, vars) -> Math.min(a.eval(context, vars), b.eval(context, vars));
            case "max" -> (context, vars) -> Math.max(a.eval(context, vars), b.eval(context, vars));
            case "clamp" -> (context, vars) -> {
                float low = b.eval(context, vars);
                float high = c.eval(context, vars);
                return Math.max(low, Math.min(high, a.eval(context, vars)));
            };
            case "lerp" -> (context, vars) -> {
                float t = c.eval(context, vars);
                return a.eval(context, vars) * (1.0F - t) + b.eval(context, vars) * t;
            };
            case "lerprotate" -> (context, vars) -> {
                float from = a.eval(context, vars);
                float to = b.eval(context, vars);
                float t = c.eval(context, vars);
                float delta = ((to - from) % 360.0F + 540.0F) % 360.0F - 180.0F;
                return from + delta * t;
            };
            case "hermite_blend" -> (context, vars) -> {
                float t = a.eval(context, vars);
                return 3.0F * t * t - 2.0F * t * t * t;
            };
            case "min_angle" -> (context, vars) -> {
                float angle = a.eval(context, vars) % 360.0F;
                return angle > 180.0F ? angle - 360.0F : (angle < -180.0F ? angle + 360.0F : angle);
            };
            // Chance has no place in a pose that must be the same on every
            // client; the middle of the range stands in for the roll.
            case "random" -> (context, vars) -> (a.eval(context, vars) + b.eval(context, vars)) * 0.5F;
            case "random_integer" -> (context, vars) -> (float) Math.floor((a.eval(context, vars) + b.eval(context, vars)) * 0.5F);
            case "die_roll" -> (context, vars) -> a.eval(context, vars) * (b.eval(context, vars) + c.eval(context, vars)) * 0.5F;
            case "die_roll_integer" -> (context, vars) -> (float) Math.floor(a.eval(context, vars) * (b.eval(context, vars) + c.eval(context, vars)) * 0.5F);
            default -> ZERO;
        };

        return fold(result);
    }

    private static float clampUnit(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }

    /** Folds an expression to a number when it cannot depend on anything. */
    private static Expr fold(Expr expr) {
        // A cheap probe: evaluated twice against contexts that disagree on
        // everything, an expression that returns the same both times and
        // never asked a question is a constant.
        boolean[] asked = new boolean[1];
        Context first = name -> {
            asked[0] = true;
            return 0.0F;
        };
        Context second = name -> {
            asked[0] = true;
            return 1.0F;
        };
        // A variable read counts as a question too.
        Map<String, Float> none = new HashMap<>() {
            @Override
            public Float get(Object key) {
                asked[0] = true;
                return null;
            }
        };

        try {
            float a = expr.eval(first, none);
            float b = expr.eval(second, none);

            if (!asked[0] && a == b) {
                return new Const(a);
            }
        } catch (RuntimeException ignored) {
        }

        return expr;
    }

    /* ------------------------------------------------------------------
     * Lexing
     * ------------------------------------------------------------------ */

    @Nullable
    private String identifier() {
        skipWhitespace();
        char c = peek();

        if (!Character.isLetter(c) && c != '_') {
            return null;
        }

        int start = this.position;

        while (this.position < this.text.length()) {
            char d = this.text.charAt(this.position);

            if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                this.position++;
            } else {
                break;
            }
        }

        return this.text.substring(start, this.position);
    }

    private boolean startsWithWord(String word) {
        if (!this.text.regionMatches(true, this.position, word, 0, word.length())) {
            return false;
        }

        char after = peekAt(word.length());
        return !Character.isLetterOrDigit(after) && after != '_' && after != '.';
    }

    private char peek() {
        return this.position < this.text.length() ? this.text.charAt(this.position) : '\0';
    }

    private char peekAt(int offset) {
        int index = this.position + offset;
        return index < this.text.length() ? this.text.charAt(index) : '\0';
    }

    private void skipWhitespace() {
        while (this.position < this.text.length() && Character.isWhitespace(this.text.charAt(this.position))) {
            this.position++;
        }
    }
}
