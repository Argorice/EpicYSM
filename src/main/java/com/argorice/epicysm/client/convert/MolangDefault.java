package com.argorice.epicysm.client.convert;

import javax.annotation.Nullable;

/**
 * Evaluates a molang expression in its DEFAULT state: every variable and
 * query (v.*, q.*, ysm.*, ctrl.*, math.* and any other call) is 0, exactly
 * like an uninitialized molang variable. Model authors rely on this when
 * they gate geometry with expressions like "v.show_car && v.anim_ctrl" -
 */
public final class MolangDefault {
    private final String text;
    private int position;

    private MolangDefault(String text) {
        this.text = text;
    }

    @Nullable
    public static Float evaluate(String expression) {
        if (expression == null || expression.contains(";") || expression.contains("=") && !expression.contains("==")
                && !expression.contains("!=") && !expression.contains(">=") && !expression.contains("<=")) {
            return null;
        }

        MolangDefault parser = new MolangDefault(expression);

        try {
            float value = parser.ternary();
            parser.skipWhitespace();
            return parser.position >= parser.text.length() ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private float ternary() {
        float condition = or();
        skipWhitespace();

        if (peek() == '?') {
            this.position++;
            float whenTrue = ternary();
            skipWhitespace();

            if (peek() != ':') {
                throw new IllegalArgumentException("expected :");
            }

            this.position++;
            float whenFalse = ternary();
            return condition != 0.0F ? whenTrue : whenFalse;
        }

        return condition;
    }

    private float or() {
        float left = and();

        while (true) {
            skipWhitespace();

            if (peek() == '|' && peekAt(1) == '|') {
                this.position += 2;
                float right = and();
                left = (left != 0.0F || right != 0.0F) ? 1.0F : 0.0F;
            } else {
                return left;
            }
        }
    }

    private float and() {
        float left = equality();

        while (true) {
            skipWhitespace();

            if (peek() == '&' && peekAt(1) == '&') {
                this.position += 2;
                float right = equality();
                left = (left != 0.0F && right != 0.0F) ? 1.0F : 0.0F;
            } else {
                return left;
            }
        }
    }

    private float equality() {
        float left = comparison();

        while (true) {
            skipWhitespace();

            if (peek() == '=' && peekAt(1) == '=') {
                this.position += 2;
                left = left == comparison() ? 1.0F : 0.0F;
            } else if (peek() == '!' && peekAt(1) == '=') {
                this.position += 2;
                left = left != comparison() ? 1.0F : 0.0F;
            } else {
                return left;
            }
        }
    }

    private float comparison() {
        float left = additive();

        while (true) {
            skipWhitespace();
            char c = peek();

            if (c == '<' || c == '>') {
                boolean less = c == '<';
                boolean orEqual = peekAt(1) == '=';
                this.position += orEqual ? 2 : 1;
                float right = additive();

                if (less) {
                    left = (orEqual ? left <= right : left < right) ? 1.0F : 0.0F;
                } else {
                    left = (orEqual ? left >= right : left > right) ? 1.0F : 0.0F;
                }
            } else {
                return left;
            }
        }
    }

    private float additive() {
        float left = multiplicative();

        while (true) {
            skipWhitespace();
            char c = peek();

            if (c == '+') {
                this.position++;
                left += multiplicative();
            } else if (c == '-') {
                this.position++;
                left -= multiplicative();
            } else {
                return left;
            }
        }
    }

    private float multiplicative() {
        float left = unary();

        while (true) {
            skipWhitespace();
            char c = peek();

            if (c == '*') {
                this.position++;
                left *= unary();
            } else if (c == '/') {
                this.position++;
                float right = unary();
                left = right == 0.0F ? 0.0F : left / right;
            } else {
                return left;
            }
        }
    }

    private float unary() {
        skipWhitespace();
        char c = peek();

        if (c == '!') {
            this.position++;
            return unary() == 0.0F ? 1.0F : 0.0F;
        }

        if (c == '-') {
            this.position++;
            return -unary();
        }

        if (c == '+') {
            this.position++;
            return unary();
        }

        return primary();
    }

    private float primary() {
        skipWhitespace();
        char c = peek();

        if (c == '(') {
            this.position++;
            float value = ternary();
            skipWhitespace();

            if (peek() != ')') {
                throw new IllegalArgumentException("expected )");
            }

            this.position++;
            return value;
        }

        if (c == '\'') {
            // String literal (a call argument); worth nothing numerically.
            this.position++;

            while (this.position < this.text.length() && this.text.charAt(this.position) != '\'') {
                this.position++;
            }

            if (this.position >= this.text.length()) {
                throw new IllegalArgumentException("unterminated string");
            }

            this.position++;
            return 0.0F;
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

            return Float.parseFloat(this.text.substring(start, this.position));
        }

        if (Character.isLetter(c) || c == '_') {
            while (this.position < this.text.length()) {
                char d = this.text.charAt(this.position);

                if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                    this.position++;
                } else {
                    break;
                }
            }

            skipWhitespace();

            if (peek() == '(') {
                // A call: consume the arguments, worth 0 like a variable.
                this.position++;
                skipWhitespace();

                if (peek() != ')') {
                    ternary();
                    skipWhitespace();

                    while (peek() == ',') {
                        this.position++;
                        ternary();
                        skipWhitespace();
                    }
                }

                if (peek() != ')') {
                    throw new IllegalArgumentException("expected )");
                }

                this.position++;
            }

            return 0.0F;
        }

        throw new IllegalArgumentException("unexpected character");
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
