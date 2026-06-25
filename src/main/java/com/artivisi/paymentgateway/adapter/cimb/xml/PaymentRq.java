package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Inbound CIMB payment notification (bank -> gateway). */
@Getter
@Setter
@XmlRootElement(name = CimbProtocol.PAYMENT_RQ, namespace = CimbProtocol.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class PaymentRq {

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

    @XmlElement(name = "Currency")
    private String currency;

    @XmlElement(name = "Amount")
    private BigDecimal amount;

    @XmlElement(name = "Fee")
    private BigDecimal fee;

    @XmlElement(name = "PaidAmount")
    private BigDecimal paidAmount;

    @XmlElement(name = "ReferenceNumberTransaction")
    private String referenceNumberTransaction;

    @XmlElement(name = "FlagPaymentList")
    private String flagPaymentList;

    @XmlElement(name = "CustomerName")
    private String customerName;
}
