package gov.usgs.locatorservice;

import gov.usgs.locator.LocService;
import gov.usgs.processingformats.LocationException;
import gov.usgs.processingformats.LocationRequest;
import gov.usgs.processingformats.LocationResult;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;

@Controller("/ws/locator")
public class LocatorController {

  @Value("${locator.model.path:./build/models/}")
  protected String modelPath;

  @Value("${locator.serialized.path:./build/models/}")
  protected String serializedPath;

  @Get(uri = "/", produces = MediaType.TEXT_HTML)
  @Hidden
  public HttpResponse getIndex() {
    return HttpResponse.redirect(URI.create("/ws/locator/index.html"));
  }

  @Post(uri = "/locate", consumes = MediaType.APPLICATION_JSON)
  public LocationResult getLocation(@Body LocationRequest request) throws LocationException {
    LocService service = new LocService(modelPath, serializedPath);
    return service.getLocation(request);
  }
}
