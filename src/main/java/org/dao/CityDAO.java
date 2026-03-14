package org.dao;

import org.domain.City;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

import java.util.List;

public class CityDAO {

    private final SessionFactory sessionFactory;

    public CityDAO(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public List<City> getAll() {
        Query<City> query = sessionFactory.getCurrentSession()
                .createQuery("select c from City c join fetch c.country", City.class);
        return query.list();
    }

    public City getById(Integer id) {
        Query<City> query = sessionFactory.getCurrentSession()
                .createQuery("select c from City c join fetch c.country where c.id = :id", City.class);
        query.setParameter("id", id);
        return query.uniqueResult();
    }
}

