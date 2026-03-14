package org.dao;

import org.domain.Country;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

import java.util.List;

public class CountryDAO {

    private final SessionFactory sessionFactory;

    public CountryDAO(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public List<Country> getAll() {
        Query<Country> query = sessionFactory.getCurrentSession()
                .createQuery("select distinct c from Country c left join fetch c.cities left join fetch c.languages",
                        Country.class);
        return query.list();
    }

    public Country getById(Integer id) {
        Query<Country> query = sessionFactory.getCurrentSession()
                .createQuery("select c from Country c left join fetch c.cities left join fetch c.languages where c.id = :id",
                        Country.class);
        query.setParameter("id", id);
        return query.uniqueResult();
    }
}

