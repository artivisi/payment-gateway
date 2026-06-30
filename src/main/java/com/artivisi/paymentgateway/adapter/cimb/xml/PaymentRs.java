package com.artivisi.paymentgateway.adapter.cimb.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Inner body of a CIMB payment response. See {@link PaymentRsEnvelope} for the root element.
 */
@Getter
@Setter
@XmlType(name = "PaymentRs")
@XmlAccessorType(XmlAccessType.FIELD)
public class PaymentRs {

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

    @XmlElement(name = "PaymentFlag")
    private String paymentFlag;

    @XmlElement(name = "CustomerName")
    private String customerName;

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

    @XmlElement(name = "ResponseCode")
    private String responseCode;

    @XmlElement(name = "ResponseDescription")
    private String responseDescription;
}
