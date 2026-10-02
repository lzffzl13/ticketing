package com.ticketing.support;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TestDatabaseMapper {
    @Select("SELECT DATABASE()")
    String currentDatabase();
}
