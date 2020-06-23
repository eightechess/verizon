package com.verizon.services;

import com.verizon.model.ParameterNameAlias;
import com.verizon.repo.ParameterNameAliasRepo;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

//ParameterNameAliasImp
@Service
@Log4j2
public class ParameterNameAliasInterfaceImp implements ParameterNameAliasInterface {

    @Autowired
    private ParameterNameAliasRepo parameterNameAliasRepo;

    @Override
    public void addParameterNameAlias(ParameterNameAlias parameterNameAlias){
        parameterNameAliasRepo.save(parameterNameAlias);
        log.info(" ParameterNameAlias pair "+ parameterNameAlias.getParameterName() + ":" + parameterNameAlias.getAlias() + " saved");
    }



    @Override
    public List<ParameterNameAlias> getAll() {
        List<ParameterNameAlias> result = new ArrayList<>();

        for( ParameterNameAlias parameterNameAlias : parameterNameAliasRepo.findAll()){
            result.add(parameterNameAlias);
        }
        return result;
    }



}
