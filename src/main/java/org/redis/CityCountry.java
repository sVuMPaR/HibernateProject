package org.redis;

import lombok.Data;

import java.util.List;

@Data
public class CityCountry {

    private Integer cityId;
    private String cityName;
    private String district;
    private Integer population;

    private Integer countryId;
    private String countryName;
    private String region;
    private Integer continent;

    private List<Language> languages;
}