package com.runeroute;

import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.Base64;
import javax.imageio.ImageIO;
import net.runelite.api.Model;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AvatarRendererTest
{
	/** A tall box standing in for a player model: 8 corners, 12 triangles. */
	private static Model box()
	{
		float[] x = {-30, 30, 30, -30, -30, 30, 30, -30};
		float[] y = {-200, -200, 0, 0, -200, -200, 0, 0};
		float[] z = {-20, -20, -20, -20, 20, 20, 20, 20};
		int[] a = {0, 0, 4, 4, 0, 0, 1, 1, 0, 0, 3, 3};
		int[] b = {1, 2, 5, 6, 4, 5, 5, 6, 3, 7, 2, 6};
		int[] c = {2, 3, 6, 7, 5, 1, 6, 2, 7, 4, 6, 7};
		int[] colors = new int[12];
		java.util.Arrays.fill(colors, (40 << 10) | (5 << 7) | 70); // a mid green
		int[] flat = new int[12];
		java.util.Arrays.fill(flat, -1);

		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class}, (proxy, m, args) ->
		{
			switch (m.getName())
			{
				case "getVerticesCount": return 8;
				case "getVerticesX": return x;
				case "getVerticesY": return y;
				case "getVerticesZ": return z;
				case "getFaceCount": return 12;
				case "getFaceIndices1": return a;
				case "getFaceIndices2": return b;
				case "getFaceIndices3": return c;
				case "getFaceColors1": return colors;
				case "getFaceColors2": return colors;
				case "getFaceColors3": return flat;
				default: return null;
			}
		});
	}

	@Test
	public void rendersTransparentPng() throws Exception
	{
		String b64 = AvatarRenderer.renderBase64(box());
		assertNotNull(b64);
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
		assertEquals(160, img.getHeight());
		assertTrue("narrower than tall", img.getWidth() < img.getHeight());
		// Middle is drawn, a corner is see-through
		assertTrue(((img.getRGB(img.getWidth() / 2, 80) >>> 24) & 0xFF) > 200);
		assertEquals(0, (img.getRGB(0, 0) >>> 24) & 0xFF);
		ImageIO.write(img, "png", new java.io.File("build/avatar-test.png"));
	}

	@Test
	public void encodesModel() throws Exception
	{
		byte[] bin = Base64.getDecoder().decode(AvatarRenderer.encodeModelBase64(box()));
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		assertEquals("RRM1", new String(bin, 0, 4, "ASCII"));
		buf.position(4);
		assertEquals(8, buf.getShort());
		assertEquals(12, buf.getShort());
		assertEquals(8 + 8 * 6 + 12 * 6 + 12 * 9, bin.length);
		java.nio.file.Files.write(java.nio.file.Paths.get("build/model-test.bin"), bin);
	}
}
