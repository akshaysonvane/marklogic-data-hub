xquery version '1.0-ml';

import module namespace trgr = 'http://marklogic.com/xdmp/triggers' at '/MarkLogic/triggers.xqy';

declare variable $ENTITY-MODEL-COLLECTION as xs:string := "http://marklogic.com/entity-services/models";
declare variable $TDE-COLLECTION as xs:string := "http://marklogic.com/entity-services/models";

declare variable $trgr:uri as xs:string external;

let $schema-xml-uri := fn:replace($trgr:uri, "\.json$", ".xsd")
let $schema-json-uri := fn:replace($trgr:uri, "\.json$", ".schema.json")
return (
  xdmp:invoke-function(
    function() {
      if (fn:doc-available($trgr:uri)) then
        xdmp:document-delete($trgr:uri)
      else (),
      if (fn:doc-available($schema-xml-uri)) then
        xdmp:document-delete($schema-xml-uri)
      else (),
      if (fn:doc-available($schema-json-uri)) then
        xdmp:document-delete($schema-json-uri)
      else ()
    }, map:entry("database", xdmp:schema-database())
  )
)
