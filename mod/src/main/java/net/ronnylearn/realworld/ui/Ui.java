package net.ronnylearn.realworld.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.ronnylearn.realworld.world.Pictures;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared look of the mod's screens: colours, panels and small formatting helpers. */
public final class Ui {
	public static final int TEXT = 0xFFFFFFFF;
	public static final int DIM = 0xFFA8B0B8;
	public static final int FAINT = 0xFF6E767E;
	public static final int ACCENT = 0xFF7DDB6A;
	public static final int ACCENT_DARK = 0xFF3F8F38;
	public static final int SELECTION = 0xFF4FB3FF;
	public static final int WARN = 0xFFFFC34D;
	public static final int BAD = 0xFFFF6B6B;
	public static final int PANEL = 0xD0101317;
	public static final int PANEL_LIGHT = 0xD01A1F25;
	public static final int BORDER = 0xFF2C333B;
	public static final int BORDER_LIGHT = 0xFF434C56;

	private Ui() {
	}

	/** A dark panel with a hairline border and a lighter top edge, like vanilla's in-world menus. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		g.outline(x, y, w, h, BORDER);
		g.horizontalLine(x + 1, x + w - 2, y + 1, 0x18FFFFFF);
	}

	/** Draws a picture into the box, cropped to fill it (like CSS object-fit: cover). */
	public static void cover(GuiGraphicsExtractor g, Pictures.Picture picture, int x, int y, int w, int h) {
		float boxRatio = (float) w / h;
		float picRatio = (float) picture.width() / picture.height();
		float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
		if (picRatio > boxRatio) {
			float keep = boxRatio / picRatio;
			u0 = (1 - keep) / 2;
			u1 = u0 + keep;
		} else {
			float keep = picRatio / boxRatio;
			v0 = (1 - keep) / 2;
			v1 = v0 + keep;
		}
		g.blit(picture.id(), x, y, x + w, y + h, u0, u1, v0, v1);
	}

	/** Draws the whole picture inside the box, centred, on a dark backing (like object-fit: contain). */
	public static void contain(GuiGraphicsExtractor g, Pictures.Picture picture, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, 0xFF14181C);
		double scale = Math.min((double) w / picture.width(), (double) h / picture.height());
		int pw = Math.max(1, (int) Math.round(picture.width() * scale));
		int ph = Math.max(1, (int) Math.round(picture.height() * scale));
		int px = x + (w - pw) / 2, py = y + (h - ph) / 2;
		g.blit(picture.id(), px, py, px + pw, py + ph, 0f, 1f, 0f, 1f);
	}

	/** Text cut to a width, with an ellipsis when it does not fit. */
	public static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
	}

	public static Component tr(String key, Object... args) {
		return Component.translatable(key, args);
	}

	public static String size(long bytes) {
		if (bytes < 0) {
			return "…";
		}
		if (bytes < 1024 * 1024) {
			return tr("realworld.unit.kb", Math.max(1, bytes / 1024)).getString();
		}
		double mb = bytes / (1024.0 * 1024.0);
		return mb < 1024
			? tr("realworld.unit.mb", String.format(Locale.ROOT, mb < 10 ? "%.1f" : "%.0f", mb)).getString()
			: tr("realworld.unit.gb", String.format(Locale.ROOT, "%.1f", mb / 1024)).getString();
	}

	public static String distance(double meters) {
		if (meters < 1000) {
			return tr("realworld.unit.m", Math.round(meters)).getString();
		}
		String km = String.format(Locale.ROOT, meters < 10_000 ? "%.2f" : "%.1f", meters / 1000);
		// "1.00" reads as "1", "2.50" as "2.5".
		km = km.contains(".") ? km.replaceAll("0+$", "").replaceAll("\\.$", "") : km;
		return tr("realworld.unit.km", km).getString();
	}

	public static String area(double km2) {
		return tr("realworld.unit.km2", String.format(Locale.ROOT, km2 < 0.1 ? "%.3f" : km2 < 10 ? "%.2f" : "%.1f", km2)).getString();
	}

	public static String date(long millis) {
		return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(millis));
	}

	public static String duration(long millis) {
		long s = millis / 1000;
		return s < 60 ? tr("realworld.unit.sec", s).getString()
			: tr("realworld.unit.min_sec", s / 60, String.format(Locale.ROOT, "%02d", s % 60)).getString();
	}

	public static int alpha(int color, float alpha) {
		int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
		return (a << 24) | (color & 0xFFFFFF);
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF, aa = (a >>> 24);
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF, ba = (b >>> 24);
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
			| (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
	}

	public static float clamp01(float v) {
		return Math.max(0, Math.min(1, v));
	}
}
