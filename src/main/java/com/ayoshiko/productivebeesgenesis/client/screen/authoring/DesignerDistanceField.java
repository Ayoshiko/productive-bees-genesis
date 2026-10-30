package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

/** 二维欧氏距离变换只在新字形烘焙时执行；不在每帧重算轮廓。 */
final class DesignerDistanceField {
	private static final float FAR = 1.0e12F;

	private DesignerDistanceField() {
	}

	static byte[] create(byte[] coverage, int width, int height, int spread) {
		float[] inside = new float[coverage.length];
		float[] outside = new float[coverage.length];
		for (int index = 0; index < coverage.length; index++) {
			boolean filled = (coverage[index] & 255) >= 128;
			inside[index] = filled ? 0 : FAR;
			outside[index] = filled ? FAR : 0;
		}
		transform(inside, width, height);
		transform(outside, width, height);
		byte[] result = new byte[coverage.length];
		for (int index = 0; index < result.length; index++) {
			double distance = Math.sqrt(outside[index]) - Math.sqrt(inside[index]);
			result[index] = (byte) Math.max(0, Math.min(255, Math.round(128 + distance * 127 / spread)));
		}
		return result;
	}

	private static void transform(float[] values, int width, int height) {
		int length = Math.max(width, height);
		float[] input = new float[length];
		float[] output = new float[length];
		int[] sites = new int[length];
		double[] boundaries = new double[length + 1];
		for (int y = 0; y < height; y++) {
			System.arraycopy(values, y * width, input, 0, width);
			line(input, output, width, sites, boundaries);
			System.arraycopy(output, 0, values, y * width, width);
		}
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				input[y] = values[y * width + x];
			}
			line(input, output, height, sites, boundaries);
			for (int y = 0; y < height; y++) {
				values[y * width + x] = output[y];
			}
		}
	}

	private static void line(float[] input, float[] output, int length, int[] sites, double[] boundaries) {
		int last = 0;
		sites[0] = 0;
		boundaries[0] = Double.NEGATIVE_INFINITY;
		boundaries[1] = Double.POSITIVE_INFINITY;
		for (int position = 1; position < length; position++) {
			double intersection;
			while (true) {
				int previous = sites[last];
				intersection = ((double) input[position] + (double) position * position
						- input[previous] - (double) previous * previous) / (2 * (position - previous));
				if (intersection > boundaries[last] || last == 0) {
					break;
				}
				last--;
			}
			last++;
			sites[last] = position;
			boundaries[last] = intersection;
			boundaries[last + 1] = Double.POSITIVE_INFINITY;
		}
		int selected = 0;
		for (int position = 0; position < length; position++) {
			while (boundaries[selected + 1] < position) {
				selected++;
			}
			float distance = position - sites[selected];
			output[position] = distance * distance + input[sites[selected]];
		}
	}
}
