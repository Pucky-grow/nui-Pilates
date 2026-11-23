package com.yoyi.pilates.infra.storage;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.*;import org.springframework.http.*;
import org.springframework.http.server.*;import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageConverter;import java.lang.reflect.Type;import java.io.*;
/** Persist stable references, mint short-lived private URLs only after authorization. */
@RestControllerAdvice @RequiredArgsConstructor
public class FileReferenceAdvice extends RequestBodyAdviceAdapter implements ResponseBodyAdvice<Object> {
 private final FileService files;private final ObjectMapper mapper;
 @Override public boolean supports(MethodParameter m,Type t,Class<? extends HttpMessageConverter<?>> c){return true;}
 @Override public HttpInputMessage beforeBodyRead(HttpInputMessage message,MethodParameter p,Type type,Class<? extends HttpMessageConverter<?>> c)throws IOException{
  if(type==String.class || message.getHeaders().getContentType()==null || !MediaType.APPLICATION_JSON.isCompatibleWith(message.getHeaders().getContentType()))return message;
  JsonNode tree=mapper.readTree(message.getBody());walk(tree,false);
  byte[] bytes=mapper.writeValueAsBytes(tree);var headers=new HttpHeaders();headers.putAll(message.getHeaders());headers.setContentLength(bytes.length);
  return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(bytes);}public HttpHeaders getHeaders(){return headers;}};
 }
 @Override public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> c){return !p.getContainingClass().getName().endsWith("FileController");}
 @Override public Object beforeBodyWrite(Object body,MethodParameter p,MediaType media,Class<? extends HttpMessageConverter<?>> c,ServerHttpRequest request,ServerHttpResponse response){
  if(body==null||body instanceof String||body instanceof byte[]||!MediaType.APPLICATION_JSON.isCompatibleWith(media))return body;
  JsonNode tree=mapper.valueToTree(body);if(walk(tree,true))response.getHeaders().setCacheControl("private, no-store");return tree;
 }
 private boolean walk(JsonNode node,boolean output){boolean changed=false;if(node instanceof ObjectNode object){var names=new java.util.ArrayList<String>();object.fieldNames().forEachRemaining(names::add);for(String name:names){JsonNode value=object.get(name);if(value.isTextual()){String text=value.asText();if(output&&text.startsWith("/media/ref/")){object.put(name,files.resolve(text));changed=true;}else if(!output)object.put(name,files.normalize(text));}else changed|=walk(value,output);}}else if(node instanceof ArrayNode array){for(int i=0;i<array.size();i++){JsonNode value=array.get(i);if(value.isTextual()){String text=value.asText();if(output&&text.startsWith("/media/ref/")){array.set(i,TextNode.valueOf(files.resolve(text)));changed=true;}else if(!output)array.set(i,TextNode.valueOf(files.normalize(text)));}else changed|=walk(value,output);}}return changed;}
}
