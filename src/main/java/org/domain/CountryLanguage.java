package org.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "country_language")
@Getter
@Setter
public class CountryLanguage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "country_id")
    private Country country;

    @Column(name = "language")
    private String language;

    @Column(name = "is_official")
    private Boolean official;

    @Column(name = "percentage")
    private BigDecimal percentage;
}