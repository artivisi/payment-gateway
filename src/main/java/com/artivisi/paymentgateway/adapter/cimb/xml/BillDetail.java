package com.artivisi.paymentgateway.adapter.cimb.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@XmlAccessorType(XmlAccessType.FIELD)
public class BillDetail {

    @XmlElement(name = "BillCurrency")
    private String billCurrency;

    @XmlElement(name = "BillCode")
    private String billCode;

    @XmlElement(name = "BillAmount")
    private BigDecimal billAmount;

    @XmlElement(name = "BillReference")
    private String billReference;

    public BillDetail(String billCurrency, String billCode, BigDecimal billAmount, String billReference) {
        this.billCurrency = billCurrency;
        this.billCode = billCode;
        this.billAmount = billAmount;
        this.billReference = billReference;
    }
}
