package com.artivisi.paymentgateway.adapter.snap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Traces a code element to one or more entries in the SNAP reference index
 * ({@code docs/snap/snap-1.0.2.json}). The {@code value} ids are the stable
 * {@code snap.*} identifiers from that index — the single source of the code↔spec link.
 *
 * <p>Example: {@code @SnapSpec("snap.sec.transaction.signature.symmetric")}.
 *
 * @see <a href="file:../../../../../../../docs/snap/README.md">docs/snap/README.md</a>
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface SnapSpec {

    /** One or more snap.* ids from the SNAP reference index. */
    String[] value();

    /** Optional clarification of how this element relates to the cited spec id(s). */
    String note() default "";
}
