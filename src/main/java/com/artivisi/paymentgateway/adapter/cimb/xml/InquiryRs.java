package com.artivisi.paymentgateway.adapter.cimb.xml;

import com.artivisi.paymentgateway.adapter.cimb.CimbProtocol;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@XmlRootElement(name = "CIMB3rdParty_InquiryRs", namespace = CimbProtocol.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class InquiryRs {

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

    @XmlElementWrapper(name = "BillDetailList")
    @XmlElement(name = "BillDetail")
    private List<BillDetail> billDetails;

    @XmlElement(name = "Currency")
    private String currency;

    @XmlElement(name = "Amount")
    private BigDecimal amount;

    @XmlElement(name = "Fee")
    private BigDecimal fee;

    @XmlElement(name = "PaidAmount")
    private BigDecimal paidAmount;

    @XmlElement(name = "CustomerName")
    private String customerName;

    @XmlElement(name = "FlagPayment")
    private String flagPayment;

    @XmlElement(name = "ResponseCode")
    private String responseCode;

    @XmlElement(name = "ResponseDescription")
    private String responseDescription;
}
