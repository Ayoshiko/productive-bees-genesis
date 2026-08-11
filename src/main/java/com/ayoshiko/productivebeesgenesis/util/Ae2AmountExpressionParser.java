package com.ayoshiko.productivebeesgenesis.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Stack;

/**
	 * Small, client-safe copy of AE2's amount expression grammar.
	 *
	 * <p>The parser is intentionally independent from AE2 client classes so the
	 * optional AE2 integration does not load client-only AE2 classes during mod
	 * bootstrap. The supported operators match AE2: {@code +}, {@code -},
	 * {@code *}, {@code /}, {@code ^}, unary minus and parentheses.</p>
	 */
public final class Ae2AmountExpressionParser {

	private static final BigDecimal MAX_POWER_BASE = BigDecimal.valueOf(1_000_000_000L);
	private static final BigDecimal MAX_POWER_EXPONENT = BigDecimal.valueOf(30L);

	private Ae2AmountExpressionParser() {
	}

	/** Parses an AE2-style expression into a normalized decimal value. */
	public static Optional<BigDecimal> parse(String expression) {
		if (expression == null || expression.length() > 64) return Optional.empty();
		String source = expression.trim();
		if (source.startsWith("=")) source = source.substring(1);
		if (source.isBlank()) return Optional.empty();

		DecimalFormat decimalFormat = new DecimalFormat("#.######", DecimalFormatSymbols.getInstance());
		decimalFormat.setParseBigDecimal(true);
		decimalFormat.setNegativePrefix("-");
		ArrayList<Object> output = new ArrayList<>();
		Stack<Character> operators = new Stack<>();
		boolean wasValue = false;
		try {
			int index = 0;
			while (index < source.length()) {
				char current = source.charAt(index);
				if (Character.isWhitespace(current)) {
					index++;
					continue;
				}
				if (!wasValue && current != '-') {
					ParsePosition position = new ParsePosition(index);
					Number number = decimalFormat.parse(source, position);
					if (position.getErrorIndex() == -1 && position.getIndex() > index
							&& number instanceof BigDecimal decimal) {
						output.add(decimal);
						index = position.getIndex();
						wasValue = true;
						continue;
					}
				}
				char operator = current;
				if (operator == '-' && !wasValue) operator = 'u';
				wasValue = false;
				switch (operator) {
					case '(', 'u' -> operators.push(operator);
					case ')' -> {
						while (!operators.isEmpty() && operators.peek() != '(') output.add(operators.pop());
						if (operators.isEmpty()) return Optional.empty();
						operators.pop();
						wasValue = true;
					}
					case '+', '-', '*', '/', '^' -> {
						while (!operators.isEmpty() && operators.peek() != '('
								&& precedenceCheck(operators.peek(), operator)) {
							output.add(operators.pop());
						}
						operators.push(operator);
					}
					default -> {
						return Optional.empty();
					}
				}
				index++;
			}
			while (!operators.isEmpty()) {
				char operator = operators.pop();
				if (operator == '(' || operator == ')') return Optional.empty();
				output.add(operator);
			}

			Stack<BigDecimal> values = new Stack<>();
			for (Object token : output) {
				if (token instanceof BigDecimal value) {
					values.push(value);
					continue;
				}
				char operator = (Character) token;
				if (operator == 'u') {
					if (values.isEmpty()) return Optional.empty();
					values.push(values.pop().negate());
					continue;
				}
				if (values.size() < 2) return Optional.empty();
				BigDecimal right = values.pop();
				BigDecimal left = values.pop();
				values.push(apply(operator, left, right));
			}
			return values.size() == 1 ? Optional.of(values.pop().stripTrailingZeros()) : Optional.empty();
		} catch (ArithmeticException | NumberFormatException | StackOverflowError ignored) {
			return Optional.empty();
		}
	}

	/** Parses and validates a non-negative integer in the supplied inclusive range. */
	public static OptionalLong parseLong(String expression, long min, long max) {
		if (min > max) return OptionalLong.empty();
		Optional<BigDecimal> value = parse(expression);
		if (value.isEmpty()) return OptionalLong.empty();
		BigDecimal decimal = value.get();
		if (decimal.scale() > 0 || decimal.compareTo(BigDecimal.valueOf(min)) < 0
				|| decimal.compareTo(BigDecimal.valueOf(max)) > 0) return OptionalLong.empty();
		try {
			return OptionalLong.of(decimal.longValueExact());
		} catch (ArithmeticException ignored) {
			return OptionalLong.empty();
		}
	}

	private static BigDecimal apply(char operator, BigDecimal left, BigDecimal right) {
		return switch (operator) {
			case '+' -> left.add(right);
			case '-' -> left.subtract(right);
			case '*' -> left.multiply(right);
			case '/' -> {
				if (right.compareTo(BigDecimal.ZERO) == 0) throw new ArithmeticException("division by zero");
				yield left.divide(right, 8, RoundingMode.FLOOR);
			}
			case '^' -> {
				BigDecimal exponent = right.stripTrailingZeros();
				if (exponent.scale() > 0 || exponent.compareTo(BigDecimal.ZERO) < 0
						|| exponent.compareTo(MAX_POWER_EXPONENT) > 0
						|| left.abs().compareTo(MAX_POWER_BASE) > 0) {
					throw new ArithmeticException("invalid power");
				}
				yield left.pow(exponent.intValueExact());
			}
			default -> throw new ArithmeticException("invalid operator");
		};
	}

	private static boolean precedenceCheck(char first, char second) {
		return precedence(first) <= precedence(second);
	}

	private static int precedence(char operator) {
		return switch (operator) {
			case '^' -> -1;
			case 'u' -> 0;
			case '*', '/' -> 1;
			case '+', '-' -> 2;
			default -> throw new IllegalArgumentException("invalid operator");
		};
	}
}
