package com.sghmire.flow;

import android.graphics.Path;

/**
 * Turns SVG path data (the "d" attribute) into an android.graphics.Path. Covers every path
 * command, including arcs, which is all a Simple Icons logo uses.
 */
public final class SvgPath {
    private final String s;
    private int i;

    private SvgPath(String s) {
        this.s = s;
    }

    public static Path parse(String d) {
        return new SvgPath(d).run();
    }

    private Path run() {
        Path p = new Path();
        float x = 0, y = 0, startX = 0, startY = 0;
        float lastCx = 0, lastCy = 0; // last control point, for S/T reflection
        char cmd = 0, prev = 0;
        while (true) {
            skip();
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 0) {
                break;
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    x = ox + num();
                    y = oy + num();
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                    cmd = rel ? 'l' : 'L'; // further pairs are implicit line-tos
                    break;
                }
                case 'L':
                    x = ox + num();
                    y = oy + num();
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + num();
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + num();
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + num(), y1 = oy + num(), x2 = ox + num(), y2 = oy + num();
                    x = ox + num();
                    y = oy + num();
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lastCx = x2;
                    lastCy = y2;
                    break;
                }
                case 'S': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'C' || pu == 'S') ? 2 * x - lastCx : x;
                    float y1 = (pu == 'C' || pu == 'S') ? 2 * y - lastCy : y;
                    float x2 = ox + num(), y2 = oy + num();
                    x = ox + num();
                    y = oy + num();
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lastCx = x2;
                    lastCy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + num(), y1 = oy + num();
                    x = ox + num();
                    y = oy + num();
                    p.quadTo(x1, y1, x, y);
                    lastCx = x1;
                    lastCy = y1;
                    break;
                }
                case 'T': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'Q' || pu == 'T') ? 2 * x - lastCx : x;
                    float y1 = (pu == 'Q' || pu == 'T') ? 2 * y - lastCy : y;
                    x = ox + num();
                    y = oy + num();
                    p.quadTo(x1, y1, x, y);
                    lastCx = x1;
                    lastCy = y1;
                    break;
                }
                case 'A': {
                    float rx = num(), ry = num(), rot = num();
                    boolean large = flag(), sweep = flag();
                    float ex = ox + num(), ey = oy + num();
                    arc(p, x, y, ex, ey, rx, ry, rot, large, sweep);
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Z':
                    p.close();
                    x = startX;
                    y = startY;
                    break;
                default:
                    return p; // unknown command: stop rather than draw garbage
            }
            prev = cmd;
        }
        return p;
    }

    private void skip() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++;
            else break;
        }
    }

    private float num() {
        skip();
        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        boolean dot = false, digits = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                digits = true;
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && digits) {
                i++;
                if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            } else {
                break;
            }
        }
        if (start == i) throw new IllegalArgumentException("bad number at " + i);
        return Float.parseFloat(s.substring(start, i));
    }

    /** Arc flags are single 0/1 characters and may be written with no separator. */
    private boolean flag() {
        skip();
        char c = s.charAt(i++);
        return c == '1';
    }

    /** SVG endpoint arc to cubic Béziers (each at most 90 degrees). */
    private static void arc(Path p, float x1, float y1, float x2, float y2, float rx, float ry,
            float angleDeg, boolean largeArc, boolean sweep) {
        if (rx == 0 || ry == 0) {
            p.lineTo(x2, y2);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(angleDeg);
        double cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double sq = Math.sqrt(lambda);
            rx *= sq;
            ry *= sq;
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = (largeArc == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rx * y1p / ry), cyp = coef * -(ry * x1p / rx);
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0;
        double cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI;
        else if (sweep && dTheta < 0) dTheta += 2 * Math.PI;
        int segs = (int) Math.ceil(Math.abs(dTheta) / (Math.PI / 2));
        double delta = dTheta / segs;
        double t = 4.0 / 3.0 * Math.tan(delta / 4);
        double th = theta1;
        for (int k = 0; k < segs; k++) {
            double c1 = Math.cos(th), s1 = Math.sin(th);
            double c2 = Math.cos(th + delta), s2 = Math.sin(th + delta);
            double ex1 = c1 - t * s1, ey1 = s1 + t * c1;
            double ex2 = c2 + t * s2, ey2 = s2 - t * c2;
            p.cubicTo(
                    (float) (cx + rx * cos * ex1 - ry * sin * ey1), (float) (cy + rx * sin * ex1 + ry * cos * ey1),
                    (float) (cx + rx * cos * ex2 - ry * sin * ey2), (float) (cy + rx * sin * ex2 + ry * cos * ey2),
                    (float) (cx + rx * cos * c2 - ry * sin * s2), (float) (cy + rx * sin * c2 + ry * cos * s2));
            th += delta;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double a = Math.atan2(uy, ux), b = Math.atan2(vy, vx);
        double d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }
}
