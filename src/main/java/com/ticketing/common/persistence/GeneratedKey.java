package com.ticketing.common.persistence;

/** MyBatis writes the database-generated ID here; business records remain immutable. */
public class GeneratedKey {
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
