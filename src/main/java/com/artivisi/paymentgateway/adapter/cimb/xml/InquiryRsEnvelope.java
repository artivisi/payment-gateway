package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/** Root element of a CIMB inquiry response: {@code <CIMB3rdParty_InquiryRs><InquiryRs>...</InquiryRs>}. */
@Getter
@Setter
@XmlRootElement(name = "CIMB3rdParty_InquiryRs", namespace = CimbProtocol.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class InquiryRsEnvelope {

    @XmlElement(name = "InquiryRs", required = true)
    private InquiryRs inquiryRs = new InquiryRs();
}
