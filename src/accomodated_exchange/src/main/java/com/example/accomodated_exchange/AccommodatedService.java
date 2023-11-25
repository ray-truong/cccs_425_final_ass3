package com.example.accomodated_exchange;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

@Service
public class AccommodatedService {
    private final Environment env;
    private final RestTemplate restTemplate;
    private final Map<String, Double> exchangeRates = new HashMap<>();
    private final Map<String, NavigableMap<Double, Double>> discountRates = new HashMap<>();

    public AccommodatedService(Environment env, RestTemplate restTemplate) {
        this.env = env;
        this.restTemplate = restTemplate;
        loadExchangeRates();
    }

    private void loadExchangeRates() {
        ClassPathResource resource = new ClassPathResource("accomodated.csv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            String line;
            reader.readLine(); // Skip header line
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    String currency = parts[0].trim();
                    Double minAmount = Double.parseDouble(parts[1].trim());
                    Double discount = Double.parseDouble(parts[2].trim());

                    discountRates.putIfAbsent(currency, new TreeMap<>());
                    discountRates.get(currency).put(minAmount, discount);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load exchange rates", e);
        }
    }



    public AccommodatedExchange getAccommodatedExchange(String from, String to, double amount) {
        String externalServicePort = env.getProperty("external.service.port");
        ResponseEntity<ExchangeRateResponse> response = restTemplate.getForEntity(
                "http://localhost:" + externalServicePort + "/exchange-rate/basic/" + from + "/" + to,
                ExchangeRateResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to retrieve exchange rate");
        }

        double rate = response.getBody().getRate();

        // Retrieve the applicable discount rate
        NavigableMap<Double, Double> currencyDiscounts = discountRates.get(from);
        if (currencyDiscounts == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No discount rates found for currency: " + from);
        }
        Map.Entry<Double, Double> discountEntry = currencyDiscounts.floorEntry(amount);
        if (discountEntry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No applicable discount rate found for the amount: " + amount);
        }
        double discountRate = discountEntry.getValue();
        double rawAmount = amount * rate;
        double discount = discountRate / 100;
        double accommodatedAmount = rawAmount * (1 + discount);
        String time = LocalDateTime.now().toString();
        String sourceEnvironment = env.getProperty("local.server.port");
        return new AccommodatedExchange(from, to, rate, amount, discountRate, rawAmount, accommodatedAmount, time, sourceEnvironment, externalServicePort);
    }
}
class ExchangeRateResponse {
    public ExchangeRateResponse(){

    }
    public ExchangeRateResponse(double rate) {
        this.rate = rate;
    }

    public double getRate() {
        return rate;
    }

    public void setRate(double rate) {
        this.rate = rate;
    }

    private double rate;

}