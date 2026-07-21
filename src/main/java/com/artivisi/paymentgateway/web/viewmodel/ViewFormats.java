package com.artivisi.paymentgateway.web.viewmodel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Presentation-only formatting shared by the admin list/dashboard views. */
public final class ViewFormats {

    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Jakarta");

    private static final Locale ID_LOCALE = Locale.forLanguageTag("id-ID");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM", ID_LOCALE);

    private ViewFormats() {
    }

    public static String rupiah(BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        NumberFormat format = NumberFormat.getIntegerInstance(ID_LOCALE);
        return "Rp " + format.format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    /** "Today" / "Yesterday" / "21 Jul", relative to {@code now}, on the Asia/Jakarta calendar day. */
    public static String relativeDay(Instant instant, Instant now) {
        ZonedDateTime at = instant.atZone(DISPLAY_ZONE);
        ZonedDateTime today = now.atZone(DISPLAY_ZONE);
        long days = ChronoUnit.DAYS.between(at.toLocalDate(), today.toLocalDate());
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        return at.format(DAY_FORMAT);
    }

    public static String time(Instant instant) {
        return instant.atZone(DISPLAY_ZONE).format(TIME_FORMAT);
    }

    public static String shortId(String id) {
        if (id == null) {
            return "—";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
