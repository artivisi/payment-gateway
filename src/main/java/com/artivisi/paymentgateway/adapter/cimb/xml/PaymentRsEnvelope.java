package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/** Root element of a CIMB payment response: {@code <CIMB3rdParty_PaymentRs><PaymentRs>...</PaymentRs>}. */
@Getter
@Setter
@XmlRootElement(name = "CIMB3rdParty_PaymentRs", namespace = CimbProtocol.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class PaymentRsEnvelope {

    @XmlElement(name = "PaymentRs", required = true)
    private PaymentRs paymentRs = new PaymentRs();
}
