package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/** Root element of a CIMB inquiry request: {@code <CIMB3rdParty_InquiryRq><InquiryRq>...</InquiryRq>}. */
@Getter
@Setter
@XmlRootElement(name = CimbProtocol.INQUIRY_RQ, namespace = CimbProtocol.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class InquiryRqEnvelope {

    @XmlElement(name = "InquiryRq", required = true)
    private InquiryRq inquiryRq;
}
