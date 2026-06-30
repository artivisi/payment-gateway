package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/** Root element of a CIMB payment notification: {@code <CIMB3rdParty_PaymentRq><PaymentRq>...</PaymentRq>}. */
@Getter
@Setter
@XmlRootElement(name = CimbProtocol.PAYMENT_RQ, namespace = CimbProtocol.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class PaymentRqEnvelope {

    @XmlElement(name = "PaymentRq", required = true)
    private PaymentRq paymentRq;
}
