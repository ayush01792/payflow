package com.payflow.payment.entity;

import java.util.Currency;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class CurrencyConverter implements AttributeConverter<Currency, String> {

    @Override
    public String convertToDatabaseColumn(Currency currency) {
        return currency == null ? null : currency.getCurrencyCode();
    }

    @Override
    public Currency convertToEntityAttribute(String code) {
        return code == null ? null : Currency.getInstance(code);
    }
}
