package com.verizon.controller;

import com.verizon.model.ParameterNameAlias;
import com.verizon.services.ParameterNameAliasInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

//ParameterNameAliasMaintainer
@Log4j2
@RestController
public class ParameterNameAliasMaintainer {

    @Autowired
    private ParameterNameAliasInterface parameterNameAliasInterface;

    @CrossOrigin
    @GetMapping("/getAllParameterNameAlias")
    public List<ParameterNameAlias> getAlias(@RequestParam(required = false) String status){
        return parameterNameAliasInterface.getAll();
    }

    @CrossOrigin
    @PostMapping("/addParameterNameAlias")
    public void addAlias(@RequestParam ParameterNameAlias parameterNameAlias){
        parameterNameAliasInterface.addParameterNameAlias(parameterNameAlias);
    }


}
