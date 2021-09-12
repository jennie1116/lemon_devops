package com.lemon.common;

import com.alibaba.fastjson.JSONObject;
import com.lemon.data.Environment;
import com.lemon.pojo.CaseInfo;
import com.lemon.utils.Contracts;
import com.lemon.utils.JDBCUtils;
import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.config.LogConfig;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeSuite;

import java.io.*;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.config.JsonConfig.jsonConfig;
import static io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL;

public class BaseTest {
    @BeforeSuite
    public void beforSuite(){
        //把json小数的返回类型配置为BIG_DECIMAL
        RestAssured.config = RestAssured.config().jsonConfig(jsonConfig().numberReturnType(BIG_DECIMAL));
        //restassured基础 baseURI设置
        RestAssured.baseURI = Contracts.BASE_URI;
        Environment.envMap.put("Media_Type","lemonban.v2");
    }

    /**
     * 接口请求方法封装
     * @param caseInfo 请求数据实体类
     * @return 响应结果
     */
    public Response requset(CaseInfo caseInfo,String className){//数据类型为easyPOI映射出的caseinfo对象
        //添加请求、响应日志
        //创建日志文件夹
        String logFilePath="";
        if (!Contracts.SHOW_CONSOLE_LOG){//检查日志是否输出到控制台配置，，默认true，取反
            //定义日志文件夹路径，如果文件夹不存在则创建新的
            File dirFile = new File("logs\\"+className);
            if (!dirFile.exists()){
                dirFile.mkdirs();
            }
            PrintStream fileOutPutStream = null;
            //日志文件路径
            logFilePath = "logs\\"+className+"\\"+caseInfo.getInterfaceName()+"_"+caseInfo.getCaseId()+".log";
            try {
                fileOutPutStream = new PrintStream(new File(logFilePath));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            //日志配置
            // 测试用例日志单独保存(前提需要在REST-Assured请求和响应中添加log) 控制台无日志
            RestAssured.config =
                    RestAssured.config().logConfig(LogConfig.logConfig().defaultStream(fileOutPutStream));

        }


        //获取请求头，并转为map
        String requestHeader = caseInfo.getRequestHeader();
        Map<String,Object> Hmap = JSONObject.parseObject(requestHeader);
        //获取接口URL
        String url = caseInfo.getUrl();
        //获取请求参数
        String params = caseInfo.getInputParams();
        //获取请求方法
        String method = caseInfo.getMethod();
        Response res = null;
        if ("GET".equals(method)){
            if (params!=null){
                res = RestAssured.given().log().all().headers(Hmap).queryParam(params).when().get(url).then().log().all().extract().response();
            }else {
                res = RestAssured.given().log().all().headers(Hmap).when().get(url).then().log().all().extract().response();
            }
        }else if("POST".equals(method)){
            res = RestAssured.given().log().all().headers(Hmap).body(params).when().post(url).then().log().all().extract().response();
        }else if("PUT".equals(method)){
            res = RestAssured.given().log().all().headers(Hmap).body(params).when().put(url).then().log().all().extract().response();
        }else if ("PATCH".equals(method)){
            res = RestAssured.given().log().all().headers(Hmap).body(params).when().patch(url).then().log().all().extract().response();
        }

        //添加响应信息到日志文件
        //Allure定制添加接口请求响应信息
        //生成Allure报表 mvn io.qameta.allure:allure-maven:serve
        if (!Contracts.SHOW_CONSOLE_LOG){
            try {
                Allure.addAttachment("接口请求响应信息",new FileInputStream(logFilePath));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        return res;

    }

    /**
     *响应断言
     * @param res 实际响应结果
     * @param caseInfo 请求数据实体类
     */
    public void assertReponse(Response res,CaseInfo caseInfo){
        //获取期望返回结果，转为map
        String excepted = caseInfo.getExpected();
        if (excepted!=null){
            Map<String,Object> exceptedMap = JSONObject.parseObject(excepted);
            //获取所以key集合
            Set<String> allKeySet = exceptedMap.keySet();
            //增强for循环拿到所有key值
            for (String key: allKeySet) {
                //获取实际返回结果，使用jsonpath
                Object actualResult = res.jsonPath().get(key);
                //获取期望返回结果，使用map的get()
                Object exceptResult = exceptedMap.get(key);
                //断言
                Assert.assertEquals(actualResult,exceptResult);
            }
        }

    }

    public void dbAssert(CaseInfo caseInfo){
        //替换数据库 参数化替换
        caseInfo = paramsReplace(caseInfo);
        //获取数据库断言，转为map
        String dbAssertInfo = caseInfo.getDbAssertInfo();
        if (dbAssertInfo!=null){
            Map<String,Object> mapDbInfo = JSONObject.parseObject(dbAssertInfo);
            Set<String> keys = mapDbInfo.keySet();
            for (String key:keys) {
                //获取数据库读取实际结果
                Object actualResult = JDBCUtils.QuerySingleData(key);
                //获取数据库断言期望结果
                Integer exceptResult = (Integer) mapDbInfo.get(key);
                //根据数据库中读取实际返回类型做判断，把期望db返回结果，做类型转换，如果是Long类型，把期望结果强转
                if (actualResult instanceof Long){
                    Long except = exceptResult.longValue();
                    Assert.assertEquals(actualResult,except);
                }else {
                    //其他类型不需要强转
                    Assert.assertEquals(actualResult,exceptResult);
                }
            }
        }


    }

    /**
     *通过 提取表达式 将对应响应值保存到环境变量中
     * @param response 接口返回结果
     * @param caseInfo 请求参数实体类
     */
    public void extractToEnvironment(Response response,CaseInfo caseInfo){
        //拿到提取表达式，并转map
        String extractStr = caseInfo.getExtractExper();
        if (extractStr!=null){
            Map<String,Object> extractMap = JSONObject.parseObject(extractStr);
            Set<String> allKeySets = extractMap.keySet();
            for (String key:allKeySets) {
                //拿到key作为环境变量的变量名称，拿到value从响应里取到手机号存到变量里
                Object value = extractMap.get(key);
                String actResult = response.jsonPath().getString((String) value);
                if ("token".equals(key)){
                    Environment.envMap.put(key,"Bearer "+actResult);
                }else {
                    Environment.envMap.put(key,actResult);
                }

            }
        }

    }

    /**
     * 正则替换功能
     * @param orgStr 待替换字符串
     * @return 返回结果
     */
    public String regexReplace(String orgStr){
        if(orgStr!=null){
            //匹配器
            Pattern pattern = Pattern.compile("\\{\\{(.*?)\\}\\}");
            //匹配对象
            Matcher matcher = pattern.matcher(orgStr);
            String result = orgStr;//原始字符串赋值给result
            while (matcher.find()){
                String allFindStr = matcher.group(0);//获取整个匹配到正则的字符串{{mobile_phone}}
                String innerStr = matcher.group(1);//获取匹配到正则的字符串内部的值mobile_phone
                Object value = Environment.envMap.get(innerStr);//获取对应环境变量的值
                result = result.replace(allFindStr,(String)value);//把请求参数变量替换为环境变量里保存的值
            }
            return result;
        }else {
            return orgStr;
        }
    }

    public CaseInfo paramsReplace(CaseInfo caseInfo){
        //替换头信息
        String headerInfo = caseInfo.getRequestHeader();
        caseInfo.setRequestHeader(regexReplace(headerInfo));
        //替换接口URL
        String url = caseInfo.getUrl();
        caseInfo.setUrl(regexReplace(url));
        //替换请求参数
        String inputParams = caseInfo.getInputParams();
        caseInfo.setInputParams(regexReplace(inputParams));
        //替换期望结果
        String expected = caseInfo.getExpected();
        caseInfo.setExpected(regexReplace(expected));
        //替换数据库断言
        String exceptDB = caseInfo.getDbAssertInfo();
        caseInfo.setDbAssertInfo(regexReplace(exceptDB));
        return caseInfo;
    }



}
