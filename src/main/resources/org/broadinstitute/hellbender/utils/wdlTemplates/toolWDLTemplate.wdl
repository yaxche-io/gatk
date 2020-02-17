version 1.0

<#if beta?? && beta == true>
# Run ${name} (**BETA**) (GATK Version ${version})
<#elseif experimental?? && experimental == true>
# Run ${name} **EXPERIMENTAL** ${name} (GATK Version ${version})
<#else>
# Run ${name} (GATK Version ${version})
</#if>
#
# ${summary}
#
<#if arguments.all?size != 0>
<#if arguments.positional?size != 0>
<@commentargumentlist name="Positional Arguments" argsToUse=arguments.positional/>
</#if>
<#if arguments.required?size != 0>
<@commentargumentlist name="Required Arguments" argsToUse=arguments.required/>
#
</#if>
<#if arguments.optional?size != 0>
<@commentargumentlist name="Optional Tool Arguments" argsToUse=arguments.optional/>
#
</#if>
<#if arguments.common?size != 0>
<@commentargumentlist name="Optional Common Arguments" argsToUse=arguments.common/>
</#if>
</#if>

<#if structs?? && structs?size != 0>
<@structslist structtypes=structs/>
</#if>

workflow ${name} {

  input {
    <#if arguments.all?size != 0>
        <@workflowinput name="Positional Arguments" argsToUse=arguments.positional/>
        <@workflowinput name="Required Arguments" argsToUse=arguments.required/>
        <@workflowinput name="Optional Tool Arguments" argsToUse=arguments.optional/>
        <@workflowinput name="Optional Common Arguments" argsToUse=arguments.common/>
    </#if>

  }

  call ${name}Task {

    input:
    <#if arguments.all?size != 0>
        <@calltask name="Positional Arguments" argsToUse=arguments.positional/>
        <@calltask name="Required Arguments" argsToUse=arguments.required/>
        <@calltask name="Optional Tool Arguments" argsToUse=arguments.optional/>
        <@calltask name="Optional Common Arguments" argsToUse=arguments.common/>
    </#if>

  }

  output {
    <@workflowoutputs name="Workflow Outputs" outputs=RuntimeOutputs/>
  }
}


task ${name}Task {

  input {
      <#if arguments.all?size != 0>
          <@taskinput name="Positional Arguments" argsToUse=arguments.positional/>
          <@taskinput name="Required Arguments" argsToUse=arguments.required/>
          <@taskinput name="Optional Tool Arguments" argsToUse=arguments.optional/>
          <@taskinput name="Optional Common Arguments" argsToUse=arguments.common/>
      </#if>

  }

  command <<<
    gatk ${name}
  >>>

  output {
    <@taskoutputs name="Task Outputs" outputs=RuntimeOutputs/>
  }
 }

<#--------------------------------------->
<#-- Macros -->

<#macro commentargumentlist name argsToUse>
    <#if argsToUse?size != 0>
# ${name}
        <#list argsToUse as arg>
#   ${arg.name?right_pad(50)} ${arg.summary?right_pad(60)[0..*80]}
        </#list>
    </#if>
</#macro>

<#macro structslist structtypes>
    <#list structtypes as struct>
    struct ${struct.name} <#noparse>{</#noparse>
    <#list struct.fields as field>
        ${field.type} ${field.name}
    </#list>
    <#noparse>}</#noparse>
    </#list>
</#macro>

<#macro workflowinput name argsToUse>
    <#if argsToUse?size != 0>

    # ${name}
    <#list argsToUse as arg>
    ${arg.type}<#if !name?starts_with("Required")>?</#if> ${arg.name?substring(2)}
    </#list>
    </#if>
</#macro>

<#macro calltask name argsToUse>
    <#if argsToUse?size != 0>

        # ${name}
        <#list argsToUse as arg>
        ${arg.name?substring(2)?right_pad(50)} = ${arg.name?substring(2)},
        </#list>
    </#if>
</#macro>

<#macro taskinput name argsToUse>
    <#if argsToUse?size != 0>
    <#list argsToUse as arg>
    ${arg.type}<#if !name?starts_with("Required")>? </#if>  ${arg.name?substring(2)}
    </#list>
    </#if>
</#macro>

<#macro workflowoutputs name outputs>
    #output {
    #    File funcotated_file_out = Funcotate.funcotated_output_file
    #    File funcotated_file_out_idx = Funcotate.funcotated_output_file_index
    #}
    # ${name?right_pad(50)}
    <#list outputs as outputFile>
    ${outputFile}
    </#list>
</#macro>

<#macro taskoutputs name outputs>
    #output {
    #    File funcotated_output_file = "${output_file}"
    #    File funcotated_output_file_index = "${output_file_index}"
    #}
    # ${name?right_pad(50)}
    <#list outputs as outputFile>
    ${outputFile}
    </#list>
</#macro>
