package com.runeroute;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import javax.imageio.ImageIO;
import net.runelite.api.Model;

/**
 * Draws a player's 3D model to a small transparent PNG for their avatar on the website.
 *
 * This is a tiny software renderer: it reads the model's triangles and lit face colours
 * (the same data the game draws with), turns it to a slight three-quarter view, and fills
 * the triangles with a depth buffer. Nothing is read from the screen, so the picture has no
 * scenery behind it and works with any camera angle or GPU plugin.
 */
final class AvatarRenderer
{
	/** Final picture height in pixels. Width follows the model's proportions. */
	private static final int OUT_HEIGHT = 160;
	/** Draw at this many times the final size, then scale down, for smooth edges. */
	private static final int SUPERSAMPLE = 3;
	/** Turn the model a little so it doesn't look flat. Radians. */
	private static final double YAW = Math.toRadians(25);
	/** Look down slightly, like the default game camera. Radians. */
	private static final double PITCH = Math.toRadians(10);
	/** Same idea as the game's brightness setting: lower is brighter. */
	private static final double BRIGHTNESS = 0.7;

	private static final int[] PALETTE = buildPalette(BRIGHTNESS);

	private AvatarRenderer()
	{
	}

	/** Base64 PNG of the model, or null if there is nothing to draw. Call on the client thread. */
	static String renderBase64(Model model)
	{
		if (model == null || model.getFaceCount() == 0)
		{
			return null;
		}
		BufferedImage img = render(model);
		if (img == null)
		{
			return null;
		}
		try (ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(img, "png", out);
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/** Most triangles we send. Player models are usually 1,000 to 4,000. */
	private static final int MAX_FACES = 12_000;

	/**
	 * The model itself, for the website's 3D viewer, as base64 of this little-endian layout:
	 * "RRM1", uint16 vertex count, uint16 face count,
	 * then int16 x, y, z per vertex (model units, y points down),
	 * then uint16 a, b, c per face,
	 * then uint8 r, g, b for each face's three corners (already lit, like in game).
	 * Hidden faces are left out. Returns null if there is nothing to send.
	 */
	static String encodeModelBase64(Model model)
	{
		if (model == null || model.getFaceCount() == 0)
		{
			return null;
		}
		final int vc = model.getVerticesCount();
		final int fc = model.getFaceCount();
		final float[] vx = model.getVerticesX();
		final float[] vy = model.getVerticesY();
		final float[] vz = model.getVerticesZ();
		final int[] f1 = model.getFaceIndices1();
		final int[] f2 = model.getFaceIndices2();
		final int[] f3 = model.getFaceIndices3();
		final int[] c1 = model.getFaceColors1();
		final int[] c2 = model.getFaceColors2();
		final int[] c3 = model.getFaceColors3();
		final byte[] alpha = model.getFaceTransparencies();
		if (vc > 65_535)
		{
			return null;
		}

		int shown = 0;
		for (int f = 0; f < fc; f++)
		{
			if (visible(c3, alpha, f))
			{
				shown++;
			}
		}
		if (shown == 0 || shown > MAX_FACES)
		{
			return null;
		}

		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 + vc * 6 + shown * 6 + shown * 9)
			.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		buf.put(new byte[]{'R', 'R', 'M', '1'});
		buf.putShort((short) vc);
		buf.putShort((short) shown);
		for (int i = 0; i < vc; i++)
		{
			buf.putShort(clampShort(vx[i]));
			buf.putShort(clampShort(vy[i]));
			buf.putShort(clampShort(vz[i]));
		}
		for (int f = 0; f < fc; f++)
		{
			if (visible(c3, alpha, f))
			{
				buf.putShort((short) f1[f]);
				buf.putShort((short) f2[f]);
				buf.putShort((short) f3[f]);
			}
		}
		for (int f = 0; f < fc; f++)
		{
			if (!visible(c3, alpha, f))
			{
				continue;
			}
			int a = color(c1[f]);
			int b = c3[f] == -1 ? a : color(c2[f]);
			int c = c3[f] == -1 ? a : color(c3[f]);
			for (int col : new int[]{a, b, c})
			{
				buf.put((byte) (col >> 16));
				buf.put((byte) (col >> 8));
				buf.put((byte) col);
			}
		}
		return Base64.getEncoder().encodeToString(buf.array());
	}

	private static boolean visible(int[] c3, byte[] alpha, int f)
	{
		return c3[f] != -2 && (alpha == null || (alpha[f] & 0xFF) < 254);
	}

	private static short clampShort(float v)
	{
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
	}

	private static BufferedImage render(Model model)
	{
		final int vc = model.getVerticesCount();
		final float[] vx = model.getVerticesX();
		final float[] vy = model.getVerticesY();
		final float[] vz = model.getVerticesZ();

		// Rotate every vertex. In model space y points down, and with no rotation the
		// default camera sees the model's front, so we view along +z.
		double cy = Math.cos(YAW), sy = Math.sin(YAW);
		double cp = Math.cos(PITCH), sp = Math.sin(PITCH);
		double[] px = new double[vc];
		double[] py = new double[vc];
		double[] pz = new double[vc];
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

		final int fc = model.getFaceCount();
		final int[] f1 = model.getFaceIndices1();
		final int[] f2 = model.getFaceIndices2();
		final int[] f3 = model.getFaceIndices3();
		final int[] c1 = model.getFaceColors1();
		final int[] c2 = model.getFaceColors2();
		final int[] c3 = model.getFaceColors3();
		final byte[] alpha = model.getFaceTransparencies();

		boolean[] used = new boolean[vc];
		for (int f = 0; f < fc; f++)
		{
			if (c3[f] == -2 || (alpha != null && (alpha[f] & 0xFF) >= 254))
			{
				continue; // hidden face
			}
			used[f1[f]] = used[f2[f]] = used[f3[f]] = true;
		}

		for (int i = 0; i < vc; i++)
		{
			double x = vx[i] * cy + vz[i] * sy;
			double z = -vx[i] * sy + vz[i] * cy;
			double y = vy[i] * cp - z * sp;
			z = vy[i] * sp + z * cp;
			px[i] = x;
			py[i] = y;
			pz[i] = z;
			if (used[i])
			{
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
			}
		}
		if (minX >= maxX || minY >= maxY)
		{
			return null;
		}

		// Fit the model into the picture with a small margin
		int h = OUT_HEIGHT * SUPERSAMPLE;
		double margin = 0.04 * h;
		double scale = (h - 2 * margin) / (maxY - minY);
		int w = Math.max(1, (int) Math.ceil((maxX - minX) * scale + 2 * margin));
		double ox = margin - minX * scale;
		double oy = margin - minY * scale;
		for (int i = 0; i < vc; i++)
		{
			px[i] = px[i] * scale + ox;
			py[i] = py[i] * scale + oy;
		}

		int[] pixels = new int[w * h];
		double[] depth = new double[w * h];
		Arrays.fill(depth, Double.MAX_VALUE);

		for (int f = 0; f < fc; f++)
		{
			if (c3[f] == -2 || (alpha != null && (alpha[f] & 0xFF) >= 254))
			{
				continue;
			}
			int a = f1[f], b = f2[f], c = f3[f];
			int colA = color(c1[f]);
			// -1 means one flat colour for the whole face, otherwise each corner has its own
			int colB = c3[f] == -1 ? colA : color(c2[f]);
			int colC = c3[f] == -1 ? colA : color(c3[f]);
			fillTriangle(pixels, depth, w, h,
				px[a], py[a], pz[a], colA,
				px[b], py[b], pz[b], colB,
				px[c], py[c], pz[c], colC);
		}

		BufferedImage big = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		big.setRGB(0, 0, w, h, pixels, 0, w);

		int outW = Math.max(1, Math.round((float) w / SUPERSAMPLE));
		BufferedImage out = new BufferedImage(outW, OUT_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(big, 0, 0, outW, OUT_HEIGHT, null);
		g.dispose();
		return out;
	}

	/** Fills one triangle with colours blended between its corners, keeping the nearest surface. */
	private static void fillTriangle(int[] pixels, double[] depth, int w, int h,
		double x0, double y0, double z0, int col0,
		double x1, double y1, double z1, int col1,
		double x2, double y2, double z2, int col2)
	{
		double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-9)
		{
			return;
		}
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

		for (int y = minY; y <= maxY; y++)
		{
			double sy = y + 0.5;
			for (int x = minX; x <= maxX; x++)
			{
				double sx = x + 0.5;
				double w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area;
				double w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area;
				double w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0)
				{
					continue;
				}
				double z = w0 * z0 + w1 * z1 + w2 * z2;
				int i = y * w + x;
				if (z >= depth[i])
				{
					continue;
				}
				depth[i] = z;
				int r = (int) (w0 * ((col0 >> 16) & 0xFF) + w1 * ((col1 >> 16) & 0xFF) + w2 * ((col2 >> 16) & 0xFF));
				int g = (int) (w0 * ((col0 >> 8) & 0xFF) + w1 * ((col1 >> 8) & 0xFF) + w2 * ((col2 >> 8) & 0xFF));
				int b = (int) (w0 * (col0 & 0xFF) + w1 * (col1 & 0xFF) + w2 * (col2 & 0xFF));
				pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
	}

	/** Lit face colours are indexes into the game's 16-bit HSL palette. */
	private static int color(int hsl)
	{
		return PALETTE[hsl & 0xFFFF];
	}

	/** The game's HSL to RGB table: 64 hues x 8 saturations x 128 lightness levels. */
	private static int[] buildPalette(double brightness)
	{
		int[] palette = new int[65536];
		int j = 0;
		for (int k = 0; k < 512; k++)
		{
			double hue = (k >> 3) / 64.0 + 0.0078125;
			double sat = (k & 7) / 8.0 + 0.0625;
			for (int l = 0; l < 128; l++)
			{
				double light = l / 128.0;
				double r = light, g = light, b = light;
				if (sat != 0)
				{
					double q = light < 0.5 ? light * (1 + sat) : light + sat - light * sat;
					double p = 2 * light - q;
					r = hueToRgb(p, q, wrap(hue + 1 / 3.0));
					g = hueToRgb(p, q, hue);
					b = hueToRgb(p, q, wrap(hue - 1 / 3.0));
				}
				int ri = clamp((int) (Math.pow(r, brightness) * 256));
				int gi = clamp((int) (Math.pow(g, brightness) * 256));
				int bi = clamp((int) (Math.pow(b, brightness) * 256));
				palette[j++] = (ri << 16) | (gi << 8) | bi;
			}
		}
		return palette;
	}

	private static double wrap(double t)
	{
		return t > 1 ? t - 1 : t < 0 ? t + 1 : t;
	}

	private static double hueToRgb(double p, double q, double t)
	{
		if (6 * t < 1)
		{
			return p + (q - p) * 6 * t;
		}
		if (2 * t < 1)
		{
			return q;
		}
		if (3 * t < 2)
		{
			return p + (q - p) * (2 / 3.0 - t) * 6;
		}
		return p;
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}
}
