package com.artivisi.paymentgateway.adapter.cimb.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import lombok.Getter;
import lombok.Setter;

/**
 * Inner body of a CIMB inquiry request. The bank wraps this inside
 * {@code <CIMB3rdParty_InquiryRq><InquiryRq>...</InquiryRq></CIMB3rdParty_InquiryRq>}.
 * See {@link InquiryRqEnvelope} for the root element.
 */
@Getter
@Setter
@XmlType(name = "InquiryRq")
@XmlAccessorType(XmlAccessType.FIELD)
public class InquiryRq {

    @XmlElement(name = "TransactionID")
    private String transactionID;

    @XmlElement(name = "ChannelID")
    private String channelID;

    @XmlElement(name = "TerminalID")
    private String terminalID;

    @XmlElement(name = "TransactionDate")
    private String transactionDate;

    @XmlElement(name = "CompanyCode")
    private String companyCode;

    @XmlElement(name = "CustomerKey1")
    private String customerKey1;

    @XmlElement(name = "CustomerKey2")
    private String customerKey2;

    @XmlElement(name = "CustomerKey3")
    private String customerKey3;
}
