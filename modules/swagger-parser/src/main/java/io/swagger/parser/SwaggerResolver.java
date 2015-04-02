package io.swagger.parser;

import io.swagger.parser.util.RemoteUrl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.wordnik.swagger.models.ArrayModel;
import com.wordnik.swagger.models.Model;
import com.wordnik.swagger.models.ModelImpl;
import com.wordnik.swagger.models.Operation;
import com.wordnik.swagger.models.Path;
import com.wordnik.swagger.models.RefModel;
import com.wordnik.swagger.models.Response;
import com.wordnik.swagger.models.Swagger;
import com.wordnik.swagger.models.auth.AuthorizationValue;
import com.wordnik.swagger.models.parameters.BodyParameter;
import com.wordnik.swagger.models.parameters.Parameter;
import com.wordnik.swagger.models.parameters.RefParameter;
import com.wordnik.swagger.models.properties.ArrayProperty;
import com.wordnik.swagger.models.properties.MapProperty;
import com.wordnik.swagger.models.properties.Property;
import com.wordnik.swagger.models.properties.RefProperty;
import com.wordnik.swagger.util.Json;

public class SwaggerResolver {
  Logger LOGGER = LoggerFactory.getLogger(SwaggerResolver.class);
  protected Swagger swagger;
  protected Set<ResolutionContext> resolutionSet = new HashSet<ResolutionContext>();

  protected ResolverOptions opts;
  public SwaggerResolver(){}
  public SwaggerResolver(ResolverOptions opts) {
    this.opts = opts;
  }
  public Swagger resolve(Swagger swagger, List<AuthorizationValue> auths) {
    if(swagger == null)
      return null;

    this.swagger = swagger;

    // models
    detectModelRefs();

    // operations
    detectOperationRefs();

    applyResolutions(auths);
    return this.swagger;
  }

  public void applyResolutions(List<AuthorizationValue> auths) {
    // hosts to call
    Map<String, List<Object>> hostToObjectMap = new HashMap<String, List<Object>>();
    
    Iterator<ResolutionContext> resolutionIter = resolutionSet.iterator();
    while(resolutionIter.hasNext())
    {
      ResolutionContext ctx = resolutionIter.next();
      String path = ctx.path;
      String[] parts = path.split("#");
      if(parts.length == 2) {
        String host = parts[0];
        String definitionPath = parts[1];
        List<Object> objectList = hostToObjectMap.get(host);
        if(objectList == null) {
          objectList = new ArrayList<Object>();
          hostToObjectMap.put(host, objectList);
        }

        Object mapping = ctx.object;
        Object target = ctx.parent;
        try {
          String contents = null;
          if(host.startsWith("http"))
            contents = new RemoteUrl().urlToString(host, auths);
          else
            contents = Json.mapper().writeValueAsString(swagger);
          JsonNode location = null;
          String locationName = null;
          if(contents != null) {
            location = Json.mapper().readTree(contents);
            String[] objectPath = definitionPath.split("/");
            for(String objectPathPart : objectPath) {
              LOGGER.debug("getting part " + objectPathPart);
              if(objectPathPart.length() > 0 && location != null) {
                location = location.get(objectPathPart);
                locationName = objectPathPart;
              }
            }
          }
          if(location != null) {
            // convert the node to the proper type
            if(mapping instanceof Property) {
              Model model = Json.mapper().convertValue(location, Model.class);
              if(mapping instanceof RefProperty) {
                RefProperty ref = (RefProperty) mapping;
                ref.set$ref(locationName);
                swagger.addDefinition(locationName, model);
              }
            }
            else if(target instanceof Parameter) {
              if(mapping instanceof RefModel) {
                Model model = Json.mapper().convertValue(location, Model.class);
                RefModel ref = (RefModel) mapping;
                ref.set$ref(locationName);
                swagger.addDefinition(locationName, model);
              }
            }
            else if(target instanceof Operation) {

              // get the operation position
              Operation operation = (Operation) target;
              int position = 0;
              for(Parameter param : operation.getParameters()) {

                if(param instanceof RefParameter) {
                  RefParameter ref = (RefParameter) param;
                  if(ref.getSimpleRef().equals(locationName)) {
                    // found a match!
                    Parameter remoteParam = Json.mapper().convertValue(location, Parameter.class);
                    operation.getParameters().set(position, remoteParam);
                  }
                }
                position += 1;
              }
            }
          }
        }
        catch(Exception e) {
          // failed to get it
          e.printStackTrace();
        }
      }
    }
  }

  public void detectOperationRefs() {
        LOGGER.debug("detectOperationRefs()");
    Map<String, Path> paths = swagger.getPaths();
    if(paths == null) return;

    for(String pathName : paths.keySet()) {
      Path path = paths.get(pathName);
            LOGGER.debug("path name: " + pathName);
      List<Operation> operations = path.getOperations();
      for(Operation operation : operations) {
        if(operation.getParameters() != null) {
          for(Parameter parameter : operation.getParameters()) {
            if(parameter instanceof BodyParameter) {
              BodyParameter bp = (BodyParameter) parameter;
              if(bp.getSchema() != null && bp.getSchema() instanceof RefModel) {
                RefModel ref = (RefModel)bp.getSchema();
                if(ref.get$ref().startsWith("http")) {
                  LOGGER.debug("added reference to " + ref.get$ref());
                  resolutionSet.add(new ResolutionContext(ref, bp, "ref", ref.get$ref()));
                }
              }
            }
            else if(parameter instanceof RefParameter) {
              RefParameter ref = (RefParameter) parameter;
              LOGGER.debug("added reference to " + ref.get$ref());
              resolutionSet.add(new ResolutionContext(ref, operation, "inline", ref.get$ref()));
            }
          }
        }
        if(operation.getResponses() != null) {
          for(String responseCode : operation.getResponses().keySet()) {
            Response response = operation.getResponses().get(responseCode);
            if(response.getSchema() != null) {
              Property schema = response.getSchema();
              if(schema instanceof RefProperty) {
                RefProperty ref = (RefProperty) schema;
                if(ref.get$ref() != null && ref.get$ref().startsWith("http")) {
                  resolutionSet.add( new ResolutionContext(ref, response, "ref", ref.get$ref()));
                }
              }
            }
          }
        }
      }
    }
  }

  public void detectModelRefs() {
    Map<String, Model> models = swagger.getDefinitions();
    if(models != null) {
      for(String modelName : models.keySet()) {
        LOGGER.debug("looking at " + modelName);
        Model model = models.get(modelName);
        if(model instanceof RefModel) {
          RefModel ref = (RefModel) model;
          if(ref.get$ref() != null && ref.get$ref().startsWith("http")) {
            LOGGER.debug("added reference to " + ref.get$ref());
            resolutionSet.add( new ResolutionContext(ref, swagger.getDefinitions(), "ref", ref.get$ref()));
          }
        }
        else if(model instanceof ArrayModel) {
          ArrayModel arrayModel = (ArrayModel) model;
          if(arrayModel.getItems() != null && arrayModel.getItems() instanceof RefProperty) {
            RefProperty ref = (RefProperty)arrayModel.getItems();
            if(ref.get$ref() != null && ref.get$ref().startsWith("http")) {
              LOGGER.debug("added reference to " + ref.get$ref());
              resolutionSet.add(new ResolutionContext(ref, swagger.getDefinitions(), "ref", ref.get$ref()));
            }
          }
        }
        else if(model instanceof ModelImpl) {
          ModelImpl impl = (ModelImpl) model;
          Map<String, Property> properties = impl.getProperties();
          if(properties != null) {
            for(String propertyName : properties.keySet()) {
              Property property = properties.get(propertyName);
              if(property instanceof RefProperty) {
                RefProperty ref = (RefProperty)property;
                if(ref.get$ref() != null && ref.get$ref().startsWith("http")) {
                  LOGGER.debug("added reference to " + ref.get$ref());
                  resolutionSet.add( new ResolutionContext(ref, impl, "ref", ref.get$ref()));
                }
              }
              else if(property instanceof ArrayProperty) {
                ArrayProperty arrayProperty = (ArrayProperty) property;
                if(arrayProperty.getItems() != null && arrayProperty.getItems() instanceof RefProperty) {
                  RefProperty ref = (RefProperty)arrayProperty.getItems();
                  if(ref.get$ref() != null && ref.get$ref().startsWith("http")) {
                    LOGGER.debug("added reference to " + ref.get$ref());
                    resolutionSet.add(new ResolutionContext(ref, arrayProperty, "ref", ref.get$ref()));
                  }
                }
              }
              else if(property instanceof MapProperty) {
                MapProperty mp = (MapProperty) property;
                if(mp.getAdditionalProperties() != null && mp.getAdditionalProperties() instanceof RefProperty) {
                  RefProperty ref = (RefProperty)mp.getAdditionalProperties();
                  if(ref.get$ref() != null && ref.get$ref().startsWith("http")) {
                    LOGGER.debug("added reference to " + ref.get$ref());
                    resolutionSet.add( new ResolutionContext(ref, mp, "ref", ref.get$ref()));
                  }                
                }
              }
            }
          }
        }
      }
    }
  }

  static class ResolutionContext {
    private Object object, parent;
    private String scope;
    private String path;

    public ResolutionContext(Object object, Object parent, String scope, String path)
    {
      this.path = path;
      this.object = object;
      this.parent = parent;
      this.scope = scope;
    }
  }
}