package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/** Inbound CIMB inquiry request (bank -> gateway). Children are unqualified per CIMB's format. */
@Getter
@Setter
@XmlRootElement(name = CimbProtocol.INQUIRY_RQ, namespace = CimbProtocol.NAMESPACE)
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
